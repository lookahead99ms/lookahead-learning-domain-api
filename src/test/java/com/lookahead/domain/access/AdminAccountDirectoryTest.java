package com.lookahead.domain.access;
import com.lookahead.domain.membership.AccessSummaryService;
import com.lookahead.domain.testaccounts.TestAccountPolicy;
import com.lookahead.learning.content.security.AccountPrincipal;
import com.lookahead.learning.content.exception.AccountFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AdminAccountDirectoryTest {
 @Test void deniesBeforeEnumeration() {
  var directory=mock(AccountDirectory.class);var access=mock(StaffAccessService.class);
  var actor=new AccountPrincipal(UUID.randomUUID(),"admin","Admin",true);
  doThrow(new AccountFailure(403,"OWNER_REQUIRED","Owner required")).when(access).requireOwner(actor);
  var service=new AdminAccountDirectory(directory,access,mock(StaffAccessRepository.class),mock(AccessSummaryService.class),mock(JdbcTemplate.class),mock(TestAccountPolicy.class),mock(AccountRemovalService.class));
  assertThatThrownBy(()->service.list(actor,"",0)).isInstanceOf(AccountFailure.class);verifyNoInteractions(directory);
 }
 @Test void joinsIdentityWithCurrentDomainRolesAndAccessWithoutWrites() {
  var directory=mock(AccountDirectory.class);var access=mock(StaffAccessService.class);var staff=mock(StaffAccessRepository.class);
  var summaries=mock(AccessSummaryService.class);var jdbc=mock(JdbcTemplate.class);
  var localPolicy=mock(TestAccountPolicy.class);when(localPolicy.local()).thenReturn(true);
  var actor=new AccountPrincipal(UUID.randomUUID(),"owner","Owner",true);UUID real=UUID.randomUUID(),admin=UUID.randomUUID();
  when(directory.list("",0)).thenReturn(new AccountDirectory.Page(List.of(new AccountDirectory.Account(actor.accountId(),"owner","Owner",null),new AccountDirectory.Account(real,"real","Real","real@example.test"),new AccountDirectory.Account(admin,"admin","Admin",null)),0,false));
  when(staff.forAccount(admin)).thenReturn(new StaffAccessRepository.Assignment(UUID.randomUUID(),admin,null,Set.of("admin"),Set.of(),false,1));
  when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(admin))).thenAnswer(call->((String)call.getArgument(0)).contains("test_account_registry"));
  var summary=new AccessSummaryService.Summary("Regular · Learn",List.of("Regular · Learn"),List.of());
  when(summaries.get(actor,real)).thenReturn(summary);
  when(summaries.get(actor,actor.accountId())).thenReturn(new AccessSummaryService.Summary("Free",List.of("Free"),List.of()));
  when(summaries.get(actor,admin)).thenReturn(new AccessSummaryService.Summary("Free",List.of("Free"),List.of()));
  var removal=mock(AccountRemovalService.class);
  when(removal.options(any(),any(),anyBoolean())).thenReturn(AccountRemovalService.Options.NONE);
  when(removal.options(actor.accountId(),real,false)).thenReturn(new AccountRemovalService.Options("disabled",false,true,true));
  var result=new AdminAccountDirectory(directory,access,staff,summaries,jdbc,localPolicy,removal).list(actor,"",0);
  assertThat(result.accounts()).hasSize(3);assertThat(result.accounts().get(0).roles()).containsExactly("owner");
  assertThat(result.accounts().get(0).fullCurriculum()).isTrue();
  assertThat(result.accounts().get(1).roles()).containsExactly("user");assertThat(result.accounts().get(1).access()).isEqualTo(summary);
  assertThat(result.accounts().get(1).testAccount()).isFalse();assertThat(result.accounts().get(2).testAccount()).isTrue();
  assertThat(result.accounts().get(1).canDowngrade()).isTrue();assertThat(result.accounts().get(1).canDelete()).isFalse();
  assertThat(result.accounts().get(2).canDowngrade()).isFalse();assertThat(result.accounts().get(2).canDelete()).isFalse();
  assertThat(result.accounts().get(2).fullCurriculum()).isTrue();verify(jdbc,never()).update(anyString(),any(Object[].class));
  // DLV-969: the removal options come from the removal service; the owner's own row never asks for them.
  var realRow=result.accounts().get(1);
  assertThat(List.of(String.valueOf(realRow.signIn()),realRow.canDisable(),realRow.canEnable(),realRow.canRemove())).containsExactly("disabled",false,true,true);
  var ownRow=result.accounts().get(0);
  assertThat(List.of(String.valueOf(ownRow.signIn()),ownRow.canDisable(),ownRow.canEnable(),ownRow.canRemove())).containsExactly("null",false,false,false);
  verify(removal,never()).options(eq(actor.accountId()),eq(actor.accountId()),anyBoolean());
  // DLV-969 S36: the owner sees how many other accounts share a row's verified email; no email, no query.
  assertThat(realRow.sameEmailAccounts()).isZero();
  when(jdbc.queryForObject(contains("count(*)"),eq(Integer.class),eq("real@example.test"),eq("real@example.test"),eq(real))).thenReturn(2);
  var again=new AdminAccountDirectory(directory,access,staff,summaries,jdbc,localPolicy,removal).list(actor,"",0);
  assertThat(again.accounts().get(1).sameEmailAccounts()).isEqualTo(2);assertThat(again.accounts().get(0).sameEmailAccounts()).isZero();
  assertThat(again.accounts().get(2).sameEmailAccounts()).isZero();
  var compatible=new AdminAccountDirectory.Row(real,"real","Real",null,Set.of("user"),false,false,summary,false,false,null,false,false,false);
  assertThat(compatible.sameEmailAccounts()).isZero();assertThat(compatible.signInMethods()).isEmpty();
  assertThat(new AdminAccountDirectory.Row(real,"real","Real",null,Set.of("user"),false,false,summary,false,false,null,false,false,false,3).signInMethods()).isEmpty();
  // The owner sees every sign-in method of a row, from cloud_identities; a test account's row asks nothing.
  assertThat(again.accounts().get(1).signInMethods()).isEmpty();
  when(jdbc.query(contains("FROM cloud_identities"),any(org.springframework.jdbc.core.RowMapper.class),eq(real)))
      .thenReturn(List.of(Map.of("provider","google","email","real@example.test"),Map.of("provider","cognito")));
  var third=new AdminAccountDirectory(directory,access,staff,summaries,jdbc,localPolicy,removal).list(actor,"",0);
  assertThat(third.accounts().get(1).signInMethods()).containsExactly(Map.of("provider","google","email","real@example.test"),Map.of("provider","cognito"));
  verify(jdbc,never()).query(contains("FROM cloud_identities"),any(org.springframework.jdbc.core.RowMapper.class),eq(admin));
  // DLV-969 (owner, 2026-11-05): the owner sees each person's saved GitHub link; none is null; a test account asks nothing.
  assertThat(third.accounts().get(1).github()).isNull();
  when(jdbc.queryForList(contains("cloud_account_profile_links"),eq(String.class),eq(real))).thenReturn(List.of("https://github.com/real-person"));
  var fourth=new AdminAccountDirectory(directory,access,staff,summaries,jdbc,localPolicy,removal).list(actor,"",0);
  assertThat(fourth.accounts().get(1).github()).isEqualTo("https://github.com/real-person");
  assertThat(fourth.accounts().get(2).github()).isNull();
  verify(jdbc,never()).queryForList(contains("cloud_account_profile_links"),eq(String.class),eq(admin));
  assertThat(compatible.github()).isNull();
 }
 @Test void aNonOwnerNeverReachesAnyGithubLink() {
  var directory=mock(AccountDirectory.class);var access=mock(StaffAccessService.class);var jdbc=mock(JdbcTemplate.class);
  var actor=new AccountPrincipal(UUID.randomUUID(),"coauthor","Co-author",true);
  doThrow(new AccountFailure(403,"OWNER_REQUIRED","Owner required")).when(access).requireOwner(actor);
  var service=new AdminAccountDirectory(directory,access,mock(StaffAccessRepository.class),mock(AccessSummaryService.class),jdbc,mock(TestAccountPolicy.class),mock(AccountRemovalService.class));
  assertThatThrownBy(()->service.list(actor,"",0)).isInstanceOf(AccountFailure.class);
  verifyNoInteractions(directory,jdbc);
 }
 @Test void controllerDisablesCaching() {
  var service=mock(AdminAccountDirectory.class);var response=new org.springframework.mock.web.MockHttpServletResponse();
  new AdminAccountDirectoryController(service).list(null,"",2,response);
  assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");verify(service).list(null,"",2);
 }
}
