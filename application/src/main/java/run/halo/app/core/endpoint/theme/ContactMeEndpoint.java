package run.halo.app.core.endpoint.theme;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.GroupVersion;
import run.halo.app.infra.AnonymousUserConst;
import run.halo.app.infra.SystemConfigurableEnvironmentFetcher;
import run.halo.app.infra.exception.NotFoundException;
import run.halo.app.infra.exception.UserNotFoundException;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.notification.EmailSenderHelper;
import run.halo.app.notification.NotificationContext;
import run.halo.app.notification.NotifierConfigStore;
import run.halo.app.notification.UserNotificationPreference;
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

    private final NotifierConfigStore notifierConfigStore;

    private final EmailSenderHelper emailSenderHelper;

    private static final String EMAIL_SUBJECT = "问题反馈 - ";

    private static final String EMAIL_BODY = """
        您好！<br/>
        <br/>
        您的问题: <br/><br/>
        "%s"
        <br/><br/><br/>
        我们已经收到, 我们会尽快处理, 谢谢您的反馈！<br/>
        祝好！<br/>
        """;


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
                return request.multipartData()
                    .flatMap(multipartData -> {
                    Map<String, Part> parts = multipartData.toSingleValueMap();
                    //issue-type: 充值问题
                    // message: test
                    String issueType = getFormFieldValue(parts, "issue-type");
                    String message = getFormFieldValue(parts, "message");
                    if (StringUtils.isBlank(issueType) || StringUtils.isBlank(message)) {
                        return Mono.error(new NotFoundException("Comment setting not found"));
                    }
                    //发送邮件
                    log.info("issueType: {}, message: {}", issueType, message);
                    return sendMsgMail(spec.getEmail(), issueType, message);
                });
            });
    }

    public Mono<ServerResponse> sendMsgMail(String tagerEmail, String issueType, String issueMessage) {
        return notifierConfigStore.fetchSenderConfig(UserNotificationPreference.DEFAULT_NOTIFIER)
            .flatMap(senderConfig -> {
                var emailSenderConfig =
                    JsonUtils.DEFAULT_JSON_MAPPER.convertValue(senderConfig, EmailSenderHelper.EmailSenderConfig.class);
                if (!emailSenderConfig.isEnable()) {
                    log.error("Email notifier is disabled, skip sending email.");
                    return Mono.empty();
                }
                // 发送邮件
                JavaMailSender mailSender =
                    emailSenderHelper.createJavaMailSender(emailSenderConfig);
                //send email to user
                var message = emailSenderHelper.createMimeMessagePreparator(emailSenderConfig,
                    tagerEmail, EMAIL_SUBJECT+issueType, EMAIL_BODY.formatted(issueMessage), EMAIL_BODY.formatted(issueMessage));
                //send email to admin
                var adminMessage = emailSenderHelper.createMimeMessagePreparator(emailSenderConfig,
                    emailSenderConfig.getFeedbackCCEmail(), EMAIL_SUBJECT+issueType, "收到问题:'"+issueMessage+"'请及时处理.", "收到问题:'"+issueMessage+"'请及时处理.");
                try {
                    //send to user
                    mailSender.send(message);
                    //send to admin
                    mailSender.send(adminMessage);
                } catch (MailException e) {
                    String errorMsg =
                        "Failed to send email, please check your email configuration.";
                    log.error(errorMsg, e);
                    throw new ServerWebInputException(errorMsg, null, e);
                }
                return ServerResponse.ok().build();
            });

    }

    private String getFormFieldValue(Map<String, Part> parts, String fieldName) {
        Part part = parts.get(fieldName);
        if (part instanceof FormFieldPart) {
            return ((FormFieldPart) part).value();
        }
        return null; // 返回 null 表示该字段不存在
    }



    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.halo.run", "v1alpha1");
    }
}
