package com.lookahead.domain.access;
import com.lookahead.domain.membership.AccessSummaryService;
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
  var service=new AdminAccountDirectory(directory,access,mock(StaffAccessRepository.class),mock(AccessSummaryService.class),mock(JdbcTemplate.class));
  assertThatThrownBy(()->service.list(actor,"",0)).isInstanceOf(AccountFailure.class);verifyNoInteractions(directory);
 }
 @Test void joinsIdentityWithCurrentDomainRolesAndAccessWithoutWrites() {
  var directory=mock(AccountDirectory.class);var access=mock(StaffAccessService.class);var staff=mock(StaffAccessRepository.class);
  var summaries=mock(AccessSummaryService.class);var jdbc=mock(JdbcTemplate.class);
  var actor=new AccountPrincipal(UUID.randomUUID(),"owner","Owner",true);UUID real=UUID.randomUUID(),admin=UUID.randomUUID();
  when(directory.list("",0)).thenReturn(new AccountDirectory.Page(List.of(new AccountDirectory.Account(actor.accountId(),"owner","Owner",null),new AccountDirectory.Account(real,"real","Real","real@example.test"),new AccountDirectory.Account(admin,"admin","Admin",null)),0,false));
  when(staff.forAccount(admin)).thenReturn(new StaffAccessRepository.Assignment(UUID.randomUUID(),admin,null,Set.of("admin"),Set.of(),false,1));
  when(jdbc.queryForObject(anyString(),eq(Boolean.class),eq(admin))).thenReturn(true);
  var summary=new AccessSummaryService.Summary("Regular · Learn",List.of("Regular · Learn"),List.of());
  when(summaries.get(actor,real)).thenReturn(summary);
  var result=new AdminAccountDirectory(directory,access,staff,summaries,jdbc).list(actor,"",0);
  assertThat(result.accounts()).hasSize(3);assertThat(result.accounts().get(0).roles()).containsExactly("owner");
  assertThat(result.accounts().get(0).fullCurriculum()).isTrue();
  assertThat(result.accounts().get(1).roles()).containsExactly("user");assertThat(result.accounts().get(1).access()).isEqualTo(summary);
  assertThat(result.accounts().get(1).testAccount()).isFalse();assertThat(result.accounts().get(2).testAccount()).isTrue();
  assertThat(result.accounts().get(2).fullCurriculum()).isTrue();verify(jdbc,never()).update(anyString(),any(Object[].class));
 }
 @Test void controllerDisablesCaching() {
  var service=mock(AdminAccountDirectory.class);var response=new org.springframework.mock.web.MockHttpServletResponse();
  new AdminAccountDirectoryController(service).list(null,"",2,response);
  assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");verify(service).list(null,"",2);
 }
}
