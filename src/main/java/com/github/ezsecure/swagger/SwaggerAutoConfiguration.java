package com.github.ezsecure.swagger;

import com.fasterxml.classmate.TypeResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import springfox.documentation.builders.*;
import springfox.documentation.schema.TypeNameExtractor;
import springfox.documentation.service.*;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spi.service.contexts.SecurityContext;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger.web.ApiKeyVehicle;
import springfox.documentation.swagger.web.SecurityConfiguration;
import springfox.documentation.swagger.web.UiConfiguration;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.google.common.base.Predicates.not;
import static com.google.common.collect.Sets.newHashSet;
import static com.google.common.collect.Lists.newArrayList;

/**
 * @author youssefguenoun
 */

@Configuration
@ConditionalOnProperty(name = "swagger.enabled", havingValue = "true", matchIfMissing = false)
@EnableSwagger2
@EnableConfigurationProperties(SwaggerConfigurationProperties.class)
public class SwaggerAutoConfiguration {

    public static final String DEFAULT_INCLUDE_PATTERN = "/.*";
    public static final String securitySchemaOAuth2 = "oauth2Scheme";

    private final SwaggerConfigurationProperties swaggerConfigurationProperties;

    @Autowired
    public SwaggerAutoConfiguration(SwaggerConfigurationProperties swaggerConfigurationProperties) {
        this.swaggerConfigurationProperties = swaggerConfigurationProperties;
    }

    private Docket commonApiDoc(){
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .useDefaultResponseMessages(false)
                .forCodeGeneration(true)
                .protocols(null != swaggerConfigurationProperties.getProtocol() && swaggerConfigurationProperties.getProtocol().isHttpOnly() ? newHashSet("https") : newHashSet("http", "https"))
                .genericModelSubstitutes(ResponseEntity.class)
                .ignoredParameterTypes(java.sql.Date.class)
                .directModelSubstitute(LocalDate.class, java.sql.Date.class)
                .directModelSubstitute(ZonedDateTime.class, java.sql.Date.class)
                .directModelSubstitute(LocalDateTime.class, java.sql.Date.class)
                .select()
                .paths(not(PathSelectors.regex("/error.*")))
                .paths(PathSelectors.regex(StringUtils.hasText(swaggerConfigurationProperties.getIncludePatterns())?swaggerConfigurationProperties.getIncludePatterns():DEFAULT_INCLUDE_PATTERN))
                .build();

    }

    private ApiInfo apiInfo(){
        Contact contact = new Contact(
                swaggerConfigurationProperties.getContactName(),
                swaggerConfigurationProperties.getContactUrl(),
                swaggerConfigurationProperties.getContactEmail());

        return new ApiInfo(
                swaggerConfigurationProperties.getTitle(),
                swaggerConfigurationProperties.getDescription(),
                swaggerConfigurationProperties.getVersion(),
                swaggerConfigurationProperties.getTermsOfServiceUrl(),
                contact,
                swaggerConfigurationProperties.getLicense(),
                swaggerConfigurationProperties.getLicenseUrl(), new ArrayList<VendorExtension>());
    }

    @Bean
    @ConditionalOnProperty(name = "swagger.security.enabled", havingValue = "false")
    public Docket unsecuredDocket() {
        return commonApiDoc();
    }

    @Bean
    @ConditionalOnProperty(name = "swagger.security.enabled", havingValue = "true")
    public Docket securedDocket() {

        return commonApiDoc()
                .securityContexts(newArrayList(securityContext()))
                .securitySchemes(newArrayList(oauth()));
    }

    @Bean
    @ConditionalOnProperty(name = "swagger.security.enabled", havingValue = "true")
    SecurityContext securityContext() {
        SecurityReference securityReference = SecurityReference.builder()
                .reference(securitySchemaOAuth2)
                .scopes(scopes().toArray(new AuthorizationScope[scopes().size()]))
                .build();

        return SecurityContext.builder()
                .securityReferences(newArrayList(securityReference))
                .forPaths(PathSelectors.regex(swaggerConfigurationProperties.getIncludePatterns()))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "swagger.security.enabled", havingValue = "true")
    SecurityScheme oauth() {
        return new OAuthBuilder()
                .name(securitySchemaOAuth2)
                .grantTypes(grantTypes())
                .scopes(scopes())
                .build();
    }

    List<AuthorizationScope> scopes() {
        List<AuthorizationScope> globalScopes = new ArrayList<>();
        swaggerConfigurationProperties
                .getSecurity()
                .getGlobalScopes()
                .forEach(scope ->
                        globalScopes.add(new AuthorizationScope(scope.getName(), scope.getDescription())));
        return globalScopes;

    }

    List<GrantType> grantTypes() {
        GrantType grantType;
        String flow = swaggerConfigurationProperties.getSecurity().getFlow();
        switch (flow) {
            case "clientCredentials":
                grantType = new ClientCredentialsGrant(swaggerConfigurationProperties.getSecurity().getClientCredentialsFlow().getTokenEndpointUrl());
                break;
            case "resourceOwnerPassword":
                grantType = new ResourceOwnerPasswordCredentialsGrant(swaggerConfigurationProperties.getSecurity().getResourceOwnerPasswordFlow().getTokenEndpointUrl());
                break;
            case "authorizationCode":
                TokenEndpoint tokenEndpoint = new TokenEndpointBuilder()
                        .url(swaggerConfigurationProperties.getSecurity().getAuthorizationCodeFlow().getTokenEndpoint().getUrl())
                        .tokenName(swaggerConfigurationProperties.getSecurity().getAuthorizationCodeFlow().getTokenEndpoint().getTokenName())
                        .build();
                TokenRequestEndpoint tokenRequestEndpoint = new TokenRequestEndpointBuilder()
                        .url(swaggerConfigurationProperties.getSecurity().getAuthorizationCodeFlow().getTokenRequestEndpoint().getUrl())
                        .clientIdName(swaggerConfigurationProperties.getSecurity().getAuthorizationCodeFlow().getTokenRequestEndpoint().getClientIdName())
                        .clientSecretName(swaggerConfigurationProperties.getSecurity().getAuthorizationCodeFlow().getTokenRequestEndpoint().getClientSecretName())
                        .build();
                grantType = new AuthorizationCodeGrantBuilder()
                        .tokenEndpoint(tokenEndpoint)
                        .tokenRequestEndpoint(tokenRequestEndpoint)
                        .build();
                break;
            case "implicit":

                SwaggerConfigurationProperties.ImplicitFlow implicitFlow = swaggerConfigurationProperties.getSecurity().getImplicitFlow();
                grantType = new ImplicitGrantBuilder().loginEndpoint(new LoginEndpoint(implicitFlow.getAuthorizationEndpointUrl())).build();
                break;
            default:
                implicitFlow = swaggerConfigurationProperties.getSecurity().getImplicitFlow();
                grantType = new ImplicitGrantBuilder().loginEndpoint(new LoginEndpoint(implicitFlow.getAuthorizationEndpointUrl())).build();

        }
        if (grantType == null) {
            throw new IllegalArgumentException("No grantType was found for the desired flow. Please review your configuration.");
        }
        return newArrayList(grantType);
    }

    @Bean
    @ConditionalOnProperty(name = "swagger.security.enabled", havingValue = "true")
    SecurityConfiguration security() {
        SwaggerConfigurationProperties.Security security = swaggerConfigurationProperties.getSecurity();
        String clientId = null;
        String clientSecret = "UNDEFINED";
        switch (security.getFlow()) {
            case "clientCredentials":
                clientSecret = security.getClientCredentialsFlow().getClientSecret();
                clientId = security.getClientCredentialsFlow().getClientId();
                break;
            case "resourceOwnerPassword":
                clientSecret = security.getResourceOwnerPasswordFlow().getClientSecret();
                clientId = security.getResourceOwnerPasswordFlow().getClientId();
                break;
            case "authorizationCode":
                clientSecret = security.getAuthorizationCodeFlow().getTokenRequestEndpoint().getClientSecretName();
                clientId = security.getAuthorizationCodeFlow().getTokenRequestEndpoint().getClientIdName();
                break;
            case "implicit":
                SwaggerConfigurationProperties.ImplicitFlow implicitFlow = security.getImplicitFlow();
                clientId = implicitFlow.getClientId();
                clientSecret = "UNDEFINED";
                break;

        }
        return new SecurityConfiguration(clientId, clientSecret, security.getRealm(), security.getApiName(), security.getApiKey(), ApiKeyVehicle.HEADER, "swagger-api", " " /*scope separator*/);
    }

    @Bean
    UiConfiguration uiConfig() {
        String[] supportedSubmitMethods = UiConfiguration.Constants.DEFAULT_SUBMIT_METHODS;
        if(!swaggerConfigurationProperties.isEnableTryOutMethods()){
            supportedSubmitMethods = UiConfiguration.Constants.NO_SUBMIT_METHODS;
        }

        return new UiConfiguration(
                null,
                "none",
                "alpha",
                "schema",
                supportedSubmitMethods,
                false,
                true);
    }

    @Bean
    PageableParameterBuilderPlugin pageableParameterBuilderPlugin(TypeNameExtractor nameExtractor,
                                                                  TypeResolver resolver) {

        return new PageableParameterBuilderPlugin(nameExtractor, resolver);
    }

}
