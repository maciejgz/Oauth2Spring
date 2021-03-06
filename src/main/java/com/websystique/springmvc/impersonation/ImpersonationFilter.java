package com.websystique.springmvc.impersonation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceAware;
import org.springframework.context.support.MessageSourceAccessor;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AuthenticationDetailsSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.token.TokenStore;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.authentication.switchuser.AuthenticationSwitchUserEvent;
import org.springframework.security.web.authentication.switchuser.SwitchUserGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.filter.GenericFilterBean;

import com.websystique.springmvc.security.LiferayUserDetailService;

@Component
public class ImpersonationFilter extends GenericFilterBean implements ApplicationEventPublisherAware, MessageSourceAware {

    public static final String SPRING_SECURITY_SWITCH_USERNAME_KEY = "username";
    public static final String ROLE_PREVIOUS_ADMINISTRATOR = "ROLE_PREVIOUS_ADMINISTRATOR";


    private static final String IMEPRSONATE_PARAM = "impersonate";
    protected MessageSourceAccessor messages = SpringSecurityMessageSource.getAccessor();
    private String switchAuthorityRole = ROLE_PREVIOUS_ADMINISTRATOR;
    private AuthenticationDetailsSource<HttpServletRequest, ?> authenticationDetailsSource = new WebAuthenticationDetailsSource();
    private ApplicationEventPublisher eventPublisher;


    private UserDetailsService userDetailsService;

    private TokenStore tokenStore;


    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest servletRequest = (HttpServletRequest) request;
        if (requiresSwitchUser(servletRequest)) {
            try {
                if (userDetailsService == null) {
                    userDetailsService = WebApplicationContextUtils.getRequiredWebApplicationContext(servletRequest.getSession().getServletContext())
                            .getBean(LiferayUserDetailService.class);
                }

                if (tokenStore == null) {
                    tokenStore = WebApplicationContextUtils.getRequiredWebApplicationContext(servletRequest.getSession().getServletContext())
                            .getBean(TokenStore.class);
                }

                OAuth2Authentication adminAuth = tokenStore.readAuthentication(servletRequest.getParameter("access_token"));

                // check administrator authority
                if (adminAuth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"))) {

                    Authentication targetUser = attemptSwitchUser(servletRequest);

                    Authentication clientAuth = SecurityContextHolder.getContext().getAuthentication();
                    if (clientAuth == null) {
                        throw new BadCredentialsException(
                                "No client authentication found. Remember to put a filter upstream of the TokenEndpointAuthenticationFilter.");
                    }
                    SecurityContextHolder.getContext().setAuthentication(targetUser);
                    System.out.println("User successfully impersonated by admin: " + adminAuth.getName() + " switched to: " + targetUser.getName());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * Try to switch user
     * @param request
     * @return
     * @throws AuthenticationException
     */
    protected Authentication attemptSwitchUser(HttpServletRequest request) throws AuthenticationException {
        UsernamePasswordAuthenticationToken targetUserRequest;

        String username = request.getParameter(IMEPRSONATE_PARAM);

        if (username == null) {
            username = "";
        }

        if (this.logger.isDebugEnabled()) {
            this.logger.debug("Attempt to switch to user [" + username + "]");
        }

        UserDetails targetUser = this.userDetailsService.loadUserByUsername(username);

        targetUserRequest = createSwitchUserToken(request, targetUser);

        if (this.logger.isDebugEnabled()) {
            this.logger.debug("Switch User Token [" + targetUserRequest + "]");
        }

        // publish event
        if (this.eventPublisher != null) {
            this.eventPublisher.publishEvent(new AuthenticationSwitchUserEvent(SecurityContextHolder.getContext().getAuthentication(), targetUser));
        }
        return targetUserRequest;
    }

    private UsernamePasswordAuthenticationToken createSwitchUserToken(HttpServletRequest request, UserDetails targetUser) {

        UsernamePasswordAuthenticationToken targetUserRequest;

        Authentication currentAuth;

        try {
            // SEC-1763. Check first if we are already switched.

            currentAuth = attemptExitUser(request);
        } catch (AuthenticationCredentialsNotFoundException e) {
            currentAuth = SecurityContextHolder.getContext().getAuthentication();
        }

        GrantedAuthority switchAuthority = new SwitchUserGrantedAuthority(this.switchAuthorityRole, currentAuth);

        // get the original authorities
        Collection<? extends GrantedAuthority> orig = targetUser.getAuthorities();


        // add the new switch user authority
        List<GrantedAuthority> newAuths = new ArrayList<GrantedAuthority>(orig);
        newAuths.add(switchAuthority);

        // create the new authentication token
        targetUserRequest = new UsernamePasswordAuthenticationToken(targetUser, targetUser.getPassword(), newAuths);

        // set details
        targetUserRequest.setDetails(this.authenticationDetailsSource.buildDetails(request));

        return targetUserRequest;
    }

    protected Authentication attemptExitUser(HttpServletRequest request) throws AuthenticationCredentialsNotFoundException {
        // need to check to see if the current user has a SwitchUserGrantedAuthority
        Authentication current = SecurityContextHolder.getContext().getAuthentication();

        if (null == current) {
            throw new AuthenticationCredentialsNotFoundException(
                    this.messages.getMessage("SwitchUserFilter.noCurrentUser", "No current user associated with this request"));
        }

        // check to see if the current user did actual switch to another user
        // if so, get the original source user so we can switch back
        Authentication original = getSourceAuthentication(current);

        if (original == null) {
            this.logger.debug("Could not find original user Authentication object!");
            throw new AuthenticationCredentialsNotFoundException(
                    this.messages.getMessage("SwitchUserFilter.noOriginalAuthentication", "Could not find original Authentication object"));
        }

        // get the source user details
        UserDetails originalUser = null;
        Object obj = original.getPrincipal();

        if ((obj != null) && obj instanceof UserDetails) {
            originalUser = (UserDetails) obj;
        }

        // publish event
        if (this.eventPublisher != null) {
            this.eventPublisher.publishEvent(new AuthenticationSwitchUserEvent(current, originalUser));
        }

        return original;
    }

    private Authentication getSourceAuthentication(Authentication current) {
        Authentication original = null;

        // iterate over granted authorities and find the 'switch user' authority
        Collection<? extends GrantedAuthority> authorities = current.getAuthorities();

        for (GrantedAuthority auth : authorities) {
            // check for switch user type of authority
            if (auth instanceof SwitchUserGrantedAuthority) {
                original = ((SwitchUserGrantedAuthority) auth).getSource();
                this.logger.debug("Found original switch user granted authority [" + original + "]");
            }
        }

        return original;
    }

    protected boolean requiresSwitchUser(HttpServletRequest request) {
        String username = request.getParameter(IMEPRSONATE_PARAM);
        if (StringUtils.isNotBlank(username)) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void setMessageSource(MessageSource messageSource) {
        Assert.notNull(messageSource, "messageSource cannot be null");
        this.messages = new MessageSourceAccessor(messageSource);
    }

    public void setApplicationEventPublisher(ApplicationEventPublisher eventPublisher) throws BeansException {
        this.eventPublisher = eventPublisher;
    }



}
