/**
 * This file is part of alf.io.
 *
 * alf.io is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * alf.io is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with alf.io.  If not, see <http://www.gnu.org/licenses/>.
 */
package alfio.config;

import alfio.manager.CaptchaService;
import alfio.manager.RecaptchaService;
import alfio.manager.system.ConfigurationManager;
import alfio.manager.user.UserManager;
import alfio.model.user.Role;
import alfio.model.user.User;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.GenericFilterBean;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import java.io.IOException;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static alfio.model.system.Configuration.getSystemConfiguration;
import static alfio.model.system.ConfigurationKeys.ENABLE_CAPTCHA_FOR_LOGIN;

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

    static final String ADMIN_API = "/admin/api";
    static final String ADMIN_PUBLIC_API = "/api/v1/admin";
    static final String CSRF_SESSION_ATTRIBUTE = "CSRF_SESSION_ATTRIBUTE";
    public static final String CSRF_PARAM_NAME = "_csrf";
    public static final String OPERATOR = "OPERATOR";
    private static final String SUPERVISOR = "SUPERVISOR";
    public static final String SPONSOR = "SPONSOR";
    private static final String ADMIN = "ADMIN";
    private static final String OWNER = "OWNER";
    private static final String API_CLIENT = "API_CLIENT";
    static final String X_REQUESTED_WITH = "X-Requested-With";


    private static class BaseWebSecurity extends  WebSecurityConfigurerAdapter {

        @Autowired
        private DataSource dataSource;
        @Autowired
        private PasswordEncoder passwordEncoder;

        @Override
        public void configure(AuthenticationManagerBuilder auth) throws Exception {
            auth.jdbcAuthentication().dataSource(dataSource)
                    .usersByUsernameQuery("select username, password, enabled from ba_user where username = ?")
                    .authoritiesByUsernameQuery("select username, role from authority where username = ?")
                    .passwordEncoder(passwordEncoder);
        }
    }

    /**
     * Basic auth configuration for Public APIs.
     * The rules are valid only if the Authorization header is present and if the context path starts with /api/v1/admin
     */
    @Configuration
    @Order(0)
    public static class APIBasicAuthWebSecurity extends BaseWebSecurity {

        @Override
        protected void configure(HttpSecurity http) throws Exception {
            http.requestMatcher((request) -> request.getHeader("Authorization") != null && StringUtils.startsWith(request.getContextPath(), ADMIN_PUBLIC_API))
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                .and().csrf().disable()
                .authorizeRequests()
                .antMatchers(ADMIN_PUBLIC_API + "/**").hasRole(API_CLIENT)
                .and().httpBasic();
        }
    }

    /**
     * Basic auth configuration for Mobile App.
     * The rules are only valid if the header Authorization is present, otherwise it fallback to the
     * FormBasedWebSecurity rules.
     */
    @Configuration
    @Order(1)
    public static class BasicAuthWebSecurity extends BaseWebSecurity {

        @Override
        protected void configure(HttpSecurity http) throws Exception {
            http.requestMatcher((request) -> request.getHeader("Authorization") != null).sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and().csrf().disable()
            .authorizeRequests()
            .antMatchers(ADMIN_API + "/check-in/**").hasAnyRole(OPERATOR, SUPERVISOR)
            .antMatchers(HttpMethod.GET, ADMIN_API + "/events").hasAnyRole(OPERATOR, SUPERVISOR, SPONSOR)
            .antMatchers(ADMIN_API + "/user-type").hasAnyRole(OPERATOR, SUPERVISOR, SPONSOR)
            .antMatchers(ADMIN_API + "/**").denyAll()
            .antMatchers(HttpMethod.POST, "/api/attendees/sponsor-scan").hasRole(SPONSOR)
            .antMatchers(HttpMethod.GET, "/api/attendees/*/ticket/*").hasAnyRole(OPERATOR, SUPERVISOR)
            .antMatchers("/**").authenticated()
            .and().httpBasic();
        }
    }

    /**
     * Default form based configuration.
     */
    @Configuration
    @Order(2)
    public static class FormBasedWebSecurity extends BaseWebSecurity {

        @Autowired
        private Environment environment;

        @Autowired
        private UserManager userManager;

        @Autowired
        private CaptchaService captchaService;
        @Autowired
        private ConfigurationManager configurationManager;

        @Bean
        public CsrfTokenRepository getCsrfTokenRepository() {
            HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
            repository.setSessionAttributeName(CSRF_SESSION_ATTRIBUTE);
            repository.setParameterName(CSRF_PARAM_NAME);
            return repository;
        }

        @Override
        protected void configure(HttpSecurity http) throws Exception {

            if(environment.acceptsProfiles("!"+Initializer.PROFILE_DEV)) {
                http.requiresChannel().antMatchers("/healthz").requiresInsecure()
                    .and()
                    .requiresChannel().mvcMatchers("/**").requiresSecure();
            }

            CsrfConfigurer<HttpSecurity> configurer =
                http.exceptionHandling()
                    .accessDeniedHandler((request, response, accessDeniedException) -> {
                        if(!response.isCommitted()) {
                            if("XMLHttpRequest".equals(request.getHeader(X_REQUESTED_WITH))) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            } else if(!response.isCommitted()) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                RequestDispatcher dispatcher = request.getRequestDispatcher("/session-expired");
                                dispatcher.forward(request, response);
                            }
                        }
                    })
                    .defaultAuthenticationEntryPointFor((request, response, ex) -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED), new RequestHeaderRequestMatcher(X_REQUESTED_WITH, "XMLHttpRequest"))
                    .and()
                    .headers().cacheControl().disable()
                    .and()
                    .csrf();

            Pattern pattern = Pattern.compile("^(GET|HEAD|TRACE|OPTIONS)$");
            Predicate<HttpServletRequest> csrfWhitelistPredicate = r -> r.getRequestURI().startsWith("/api/webhook/") || pattern.matcher(r.getMethod()).matches();
            if(environment.acceptsProfiles(Initializer.PROFILE_DEBUG_CSP)) {
                csrfWhitelistPredicate = csrfWhitelistPredicate.or(r -> r.getRequestURI().equals("/report-csp-violation"));
            }
            configurer.requireCsrfProtectionMatcher(new NegatedRequestMatcher(csrfWhitelistPredicate::test));

            String[] ownershipRequired = new String[] {
                ADMIN_API + "/overridable-template",
                ADMIN_API + "/additional-services",
                ADMIN_API + "/events/*/additional-field",
                ADMIN_API + "/event/*/additional-services/",
                ADMIN_API + "/overridable-template/",
                ADMIN_API + "/events/*/promo-code",
                ADMIN_API + "/reservation/event/*/reservations/list",
                ADMIN_API + "/events/*/email/",
                ADMIN_API + "/events/*/plugin/log",
                ADMIN_API + "/event/*/waiting-queue/load",
                ADMIN_API + "/events/*/pending-payments",
                ADMIN_API + "/events/*/export.csv",
                ADMIN_API + "/events/*/sponsor-scan/export.csv",
                ADMIN_API + "/events/*/sponsor-scan/export.csv",
                ADMIN_API + "/events/*/invoices/**",
                ADMIN_API + "/reservation/event/*/*/audit"

            };

            configurer.csrfTokenRepository(getCsrfTokenRepository())
                .and()
                .authorizeRequests()
                .antMatchers(ADMIN_API + "/configuration/**", ADMIN_API + "/users/**").hasAnyRole(ADMIN, OWNER)
                .antMatchers(ADMIN_API + "/organizations/new").hasRole(ADMIN)
                .antMatchers(ADMIN_API + "/check-in/**").hasAnyRole(ADMIN, OWNER, SUPERVISOR)
                .antMatchers(HttpMethod.GET, ownershipRequired).hasAnyRole(ADMIN, OWNER)
                .antMatchers(HttpMethod.GET, ADMIN_API + "/**").hasAnyRole(ADMIN, OWNER, SUPERVISOR)
                .antMatchers(HttpMethod.POST, ADMIN_API + "/reservation/event/*/new", ADMIN_API + "/reservation/event/*/*").hasAnyRole(ADMIN, OWNER, SUPERVISOR)
                .antMatchers(HttpMethod.PUT, ADMIN_API + "/reservation/event/*/*/notify", ADMIN_API + "/reservation/event/*/*/confirm").hasAnyRole(ADMIN, OWNER, SUPERVISOR)
                .antMatchers(ADMIN_API + "/**").hasAnyRole(ADMIN, OWNER)
                .antMatchers("/admin/**/export/**").hasAnyRole(ADMIN, OWNER)
                .antMatchers("/admin/**").hasAnyRole(ADMIN, OWNER, SUPERVISOR)
                .antMatchers("/api/attendees/**").denyAll()
                .antMatchers("/**").permitAll()
                .and()
                .formLogin()
                .loginPage("/authentication")
                .loginProcessingUrl("/authenticate")
                .failureUrl("/authentication?failed")
                .and().logout().permitAll();


            //
            
            http.addFilterBefore(new RecaptchaLoginFilter(captchaService, "/authenticate", "/authentication?recaptchaFailed", configurationManager), UsernamePasswordAuthenticationFilter.class);

            if(environment.acceptsProfiles(Initializer.PROFILE_DEMO)) {
                http.addFilterAfter(new UserCreatorBeforeLoginFilter(userManager, "/authenticate"), RecaptchaLoginFilter.class);
            }
        }
        private static abstract class CaptchaLoginFilter extends GenericFilterBean{
            private final RequestMatcher requestMatcher;
            private CaptchaService captchaService;
            private final String recaptchaFailureUrl;
            private final ConfigurationManager configurationManager;
            
            CaptchaLoginFilter(CaptchaService captchaService,
                    String loginProcessingUrl,
                    String recaptchaFailureUrl,
                    ConfigurationManager configurationManager) {
			   this.requestMatcher = new AntPathRequestMatcher(loginProcessingUrl, "POST");
			   this.captchaService = captchaService;
			   this.recaptchaFailureUrl = recaptchaFailureUrl;
			   this.configurationManager = configurationManager;
			}
            protected abstract CaptchaService getCaptchaService();
            protected ConfigurationManager getConfigurationManager() {
            	return this.configurationManager;
            }
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
                HttpServletRequest req = (HttpServletRequest) request;
                HttpServletResponse res = (HttpServletResponse) response;
                
                captchaService = getCaptchaService();
                
                boolean captchaEnabled = configurationManager.getBooleanConfigValue(getSystemConfiguration(ENABLE_CAPTCHA_FOR_LOGIN), true);
                if(captchaEnabled && requestMatcher.matches(req) && !captchaService.checkCaptcha(req)) {
                    res.sendRedirect(recaptchaFailureUrl);
                    return;
                }

                chain.doFilter(request, response);
            }
        }

        private static class RecaptchaLoginFilter extends CaptchaLoginFilter {
            
            RecaptchaLoginFilter(CaptchaService captchaService,
                    String loginProcessingUrl,
                    String recaptchaFailureUrl,
                    ConfigurationManager configurationManager) {
				super(captchaService, loginProcessingUrl, recaptchaFailureUrl, configurationManager);
			}

			@Override
			protected CaptchaService getCaptchaService() {
				return new RecaptchaService(getConfigurationManager());
			}


        }


        // generate a user if it does not exists, to be used by the demo profile
        private static class UserCreatorBeforeLoginFilter extends GenericFilterBean {

            private final UserManager userManager;
            private final RequestMatcher requestMatcher;

            UserCreatorBeforeLoginFilter(UserManager userManager, String loginProcessingUrl) {
                this.userManager = userManager;
                this.requestMatcher = new AntPathRequestMatcher(loginProcessingUrl, "POST");
            }



            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
                HttpServletRequest req = (HttpServletRequest) request;

                //ensure organization/user
                if(requestMatcher.matches(req) && req.getParameter("username") != null && req.getParameter("password") != null) {
                    String username = req.getParameter("username");
                    if(!userManager.usernameExists(username)) {
                        int orgId = userManager.createOrganization(username, "Demo organization", username);
                        userManager.insertUser(orgId, username, "", "", username, Role.OWNER, User.Type.DEMO, req.getParameter("password"));
                    }
                }

                chain.doFilter(request, response);
            }
        }
    }






}
