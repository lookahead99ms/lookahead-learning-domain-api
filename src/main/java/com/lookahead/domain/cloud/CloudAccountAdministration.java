package com.lookahead.domain.cloud;

import com.lookahead.domain.DomainApiMigration;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit one-shot operator command. No HTTP endpoint, automatic grants, or email matching. */
public final class CloudAccountAdministration {
    private CloudAccountAdministration(){}
    public static void main(String[] args)throws Exception {
        if(args.length!=3&&args.length!=5)throw new IllegalArgumentException("Use admit <issuer> <subject> <username> <displayName>, or disable|enable|author-on|author-off|resolve-password-change <issuer> <subject>");
        String url=required("SPRING_FLYWAY_URL"),user=required("SPRING_FLYWAY_USER");
        DomainApiMigration.validateMigrationTarget(user,url,System.getenv().getOrDefault("LOOKAHEAD_ENVIRONMENT","local"),System.getenv("AWS_REGION"));
        String file=System.getenv("LOOKAHEAD_MIGRATION_PASSWORD_FILE"),password=System.getenv("SPRING_FLYWAY_PASSWORD");
        if(file!=null&&password!=null)throw new IllegalStateException("Choose one password source");
        if(password==null)password=Files.readString(Path.of(file!=null?file:"/run/secrets/spring.flyway.password")).stripTrailing();
        if(password.isBlank())throw new IllegalStateException("Migration password is required");
        DomainApiMigration.verifyMigrationRole(url,user,password);
        var source=com.lookahead.domain.database.DomainDatabaseConnections.boundedDataSource(url,user,password);var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);
        UUID id=apply(jdbc,new TransactionTemplate(new DataSourceTransactionManager(source)),args);
        System.out.println("Updated application account "+id+"; content grants unchanged.");
    }
    static UUID apply(JdbcTemplate jdbc,TransactionTemplate transaction,String[] args){
        if(args.length!=3&&args.length!=5)throw new IllegalArgumentException("Invalid account administration arguments");
        String action=args[0],issuer=args[1],subject=args[2];
        if(!Set.of("admit","disable","enable","author-on","author-off","resolve-password-change").contains(action)
                ||(action.equals("admit")?args.length!=5:args.length!=3)
                ||!issuer.matches("https://cognito-idp\\.[a-z]{2}-[a-z]+-\\d\\.amazonaws\\.com/[a-z]{2}-[a-z]+-\\d_[A-Za-z0-9]+")
                ||subject.isBlank()||subject.length()>256||subject.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid exact provider identity or action");
        if(action.equals("admit"))for(int i=3;i<5;i++)if(args[i].isBlank()||args[i].length()>(i==3?320:160)||args[i].codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid profile text");
        return transaction.execute(status->{
            jdbc.execute("SET LOCAL lock_timeout='5s'");
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,issuer+"\n"+subject);
            var rows=jdbc.query("SELECT account_id FROM cloud_accounts WHERE issuer=? AND subject=? FOR UPDATE",(r,i)->r.getObject(1,UUID.class),issuer,subject);
            UUID id;
            if(rows.isEmpty()) {
                if(!action.equals("admit")||args.length!=5)throw new IllegalArgumentException("Admit requires verified provider identity and profile text");
                id=UUID.randomUUID();jdbc.update("INSERT INTO platform_subjects(id) VALUES(?)",id);
                jdbc.update("INSERT INTO cloud_accounts(account_id,issuer,subject,admitted,username,display_name) VALUES(?,?,?,true,?,?)",id,issuer,subject,args[3],args[4]);
            } else {
                id=rows.getFirst();
                switch(action){
                    case "admit" -> jdbc.update("UPDATE cloud_accounts SET admitted=true WHERE account_id=?",id);
                    case "disable" -> jdbc.update("UPDATE cloud_accounts SET enabled=false WHERE account_id=?",id);
                    case "enable" -> jdbc.update("UPDATE cloud_accounts SET enabled=true WHERE account_id=?",id);
                    case "author-on" -> jdbc.update("UPDATE cloud_accounts SET author_access=true WHERE account_id=?",id);
                    case "author-off" -> jdbc.update("UPDATE cloud_accounts SET author_access=false WHERE account_id=?",id);
                    case "resolve-password-change" -> {
                        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM cloud_accounts WHERE account_id=?",Boolean.class,id)))throw new IllegalArgumentException("Disable the account and confirm no provider operation is in flight before resolving password uncertainty");
                        jdbc.update("UPDATE cloud_accounts SET credential_change_pending=false WHERE account_id=?",id);
                    }
                }
                if(action.equals("disable")||action.equals("resolve-password-change")){
                    jdbc.update("UPDATE cloud_sign_ins SET revoked_at=COALESCE(revoked_at,clock_timestamp()) WHERE account_id=?",id);
                    jdbc.update("UPDATE cloud_sign_in_challenges SET cancelled=true WHERE account_id=?",id);
                }
            }
            return id;
        });
    }
    private static String required(String key){String value=System.getenv(key);if(value==null||value.isBlank())throw new IllegalStateException(key+" is required");return value;}
}
