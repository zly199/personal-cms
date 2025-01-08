package run.halo.app.core.endpoint.theme;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.GroupVersion;
import run.halo.app.infra.AnonymousUserConst;
import run.halo.app.infra.SystemConfigurableEnvironmentFetcher;
import run.halo.app.infra.exception.NotFoundException;
import run.halo.app.infra.exception.UserNotFoundException;
import run.halo.app.security.authorization.AuthorityUtils;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

/**
 * Endpoint for site stats query APIs.
 *
 * @author guqing
 * @since 2.5.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContactMeEndpoint implements CustomEndpoint {

    private final UserService userService;

    private final SystemConfigurableEnvironmentFetcher environmentFetcher;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    public static String getCurrentTimestamp() {
        return LocalDateTime.now().format(FORMATTER);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "contactMe";
        return SpringdocRouteBuilder.route()
            .POST("email/contact-message", this::contactMe,
                builder -> builder.operationId("contactMeMessage")
                    .description("contact me message")
                    .tag(tag)
                    .response(responseBuilder()
                        .implementation(String.class)
                    )
            )
            .build();
    }



    private Mono<ServerResponse> contactMe(ServerRequest request) {

        return ReactiveSecurityContextHolder.getContext()
            .flatMap(securityContext -> {
                var authentication = securityContext.getAuthentication();
                var roles = AuthorityUtils.authoritiesToRoles(authentication.getAuthorities());
                // 校验用户登录,只包含了匿名用户则保存
                if (roles.size() == 1 && roles.contains(AnonymousUserConst.Role)) {
                    return Mono.error(new UserNotFoundException("anonymous"));
                }
                return userService.getUser(authentication.getName());
            })
            .flatMap(user ->{
                User.UserSpec spec = user.getSpec();
                String email = spec.getEmail();
                if (StringUtils.isBlank(email)) {
                    return Mono.error(new NotFoundException("Comment setting not found"));
                }
                //获取request body
                return handleFormRequest(request)
                    .flatMap(map -> {
                    //issue-type: 充值问题
                    // message: test
                    String issueType = map.get("issue-type");
                    String message = map.get("message");
                    if (StringUtils.isBlank(issueType) || StringUtils.isBlank(message)) {
                        return Mono.error(new NotFoundException("Comment setting not found"));
                    }
                    //发送邮件
                    log.info("issueType: {}, message: {}", issueType, message);
                    return sendMsgMail(spec.getEmail(), issueType, message);
                });
            });
    }

    public Mono<ServerResponse> sendMsgMail(String tagerEmail, String issueType, String message) {
        // 发送邮件 fixme
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("success"); // 或者直接返回 "success"

    }

    public Mono<Map<String, String>> handleFormRequest(ServerRequest request) {
        return request.formData()
            .map(formData -> formData
                .entrySet()
                .stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> entry.getValue().get(0) // 获取每个字段的第一个值
                ))
            );
    }



    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.halo.run", "v1alpha1");
    }
}
