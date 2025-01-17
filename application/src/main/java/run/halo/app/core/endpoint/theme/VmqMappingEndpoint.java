package run.halo.app.core.endpoint.theme;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.GroupVersion;
import run.halo.app.infra.AnonymousUserConst;
import run.halo.app.infra.SystemConfigurableEnvironmentFetcher;
import run.halo.app.infra.SystemSetting;
import run.halo.app.infra.exception.AccessDeniedException;
import run.halo.app.infra.exception.NotFoundException;
import run.halo.app.infra.exception.UserNotFoundException;
import run.halo.app.security.authorization.AuthorityUtils;
import run.halo.app.theme.finders.SiteStatsFinder;
import run.halo.app.theme.finders.vo.SiteStatsVo;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static run.halo.app.security.authorization.AuthorityUtils.ANONYMOUS_ROLE_NAME;
import static run.halo.app.security.authorization.AuthorityUtils.AUTHENTICATED_ROLE_NAME;
import static run.halo.app.security.authorization.AuthorityUtils.ROLE_PREFIX;

/**
 * Endpoint for site stats query APIs.
 *
 * @author guqing
 * @since 2.5.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VmqMappingEndpoint implements CustomEndpoint {

    private final UserService userService;

    private final RoleService roleService;

    private final SystemConfigurableEnvironmentFetcher environmentFetcher;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    public static String getCurrentTimestamp() {
        return LocalDateTime.now().format(FORMATTER);
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "VmqPublic";
        return SpringdocRouteBuilder.route()
            .GET("async/notify", this::vmqAsyncPublic,
                builder -> builder.operationId("vmqAsyncNotify")
                    .description("Vmq async notify")
                    .tag(tag)
                    .response(responseBuilder()
                        .implementation(String.class)
                    )
            ).GET("vmq/getCreateOrderSign", this::getCreateOrderSign,
                builder -> builder.operationId("getCreateOrderSign")
                    .description("Vmq async notify")
                    .tag(tag)
                    .response(responseBuilder()
                        .implementation(String.class)
                    )
            ).GET("vmq/updateUserCache", this::updateUserCache,
                builder -> builder.operationId("updateUserCache")
                    .description("Vmq async notify updateUserCache")
                    .tag(tag)
                    .response(responseBuilder()
                        .implementation(String.class)
                    )
            )
            .build();
    }

    /**
     * http://localhost:8090/apis/api.halo.run/v1alpha1/vmq/updateUserCache
     * @param request
     * @return
     */
    private Mono<ServerResponse> updateUserCache(ServerRequest request) {
        return ReactiveSecurityContextHolder.getContext()
            .flatMap(securityContext -> {
                var currentAuth = securityContext.getAuthentication();
                // 1. 增加 null 检查，防止 currentAuth 为 null
                if (currentAuth == null) {
                    return ServerResponse.status(HttpStatus.UNAUTHORIZED).build();
                }
                // 获取用户角色
                return roleService.getRolesByUsername(currentAuth.getName())
                    .concatWithValues(AUTHENTICATED_ROLE_NAME, ANONYMOUS_ROLE_NAME)
                    .collectList() // 将角色收集为列表，方便后续操作
                    .flatMap(roles -> {

                        // 处理角色逻辑
                        var existingAuthorities = currentAuth.getAuthorities().stream()
                            .map(auth -> auth.getAuthority())
                            .collect(Collectors.toSet());

                        // 添加新的角色（避免重复）
                        //Set<String> newRoles = Set.of("ROLE_role-ihcYs"); // 示例：新的角色
                        Set<String> newRoles = roles.stream()
                            .map(role -> ROLE_PREFIX + role)
                            .collect(Collectors.toSet());
                        existingAuthorities.addAll(newRoles);

                        return environmentFetcher.fetch(SystemSetting.User.GROUP, SystemSetting.User.class)
                            .flatMap(userSetting -> {
                                //existingAuthorities 删除默认角色
                                existingAuthorities.remove(ROLE_PREFIX + userSetting.getDefaultRole());
                                // 转换为 SimpleGrantedAuthority
                                var updatedAuthorities = existingAuthorities.stream()
                                    .map(SimpleGrantedAuthority::new)
                                    .toList();

                                // 创建新的 Authentication 对象
                                var updatedAuth = new UsernamePasswordAuthenticationToken(
                                    currentAuth.getPrincipal(),
                                    currentAuth.getCredentials(),
                                    updatedAuthorities
                                );

                                // 保留 details 信息
                                updatedAuth.setDetails(currentAuth.getDetails());

                                // 更新 SecurityContext 中的 Authentication
                                securityContext.setAuthentication(updatedAuth);

                                // 3. 返回正常响应
                                return ServerResponse.ok().build();
                            });
                    });
            });
    }


    private Mono<ServerResponse> getCreateOrderSign(ServerRequest request) {

        return ReactiveSecurityContextHolder.getContext()
            .flatMap(securityContext -> {
                var authentication = securityContext.getAuthentication();
                var roles = AuthorityUtils.authoritiesToRoles(authentication.getAuthorities());
                // 校验用户登录,只包含了匿名用户则保存
                if (roles.size() == 1 && roles.contains(AnonymousUserConst.Role)) {
                    return Mono.error(new UserNotFoundException("anonymous"));
                }

                return environmentFetcher.fetchComment().flatMap(comment -> {
                    if (comment == null) {
                        return Mono.error(new NotFoundException("Comment setting not found"));
                    }
                    //校验用户是否为vip,如果已经是vip,则返回错误信息
                    if (roles.contains(comment.getAllowCommentRole()) || roles.contains(AuthorityUtils.SUPER_ROLE_NAME)) {
                        return Mono.error(new AccessDeniedException("you are already vip"));
                    }
                    return userService.getUser(authentication.getName());
                });
            })
            .flatMap(user ->{
                User.UserSpec spec = user.getSpec();
                Map<String, List<String>> queryParams = request.queryParams();

                //payId	字符串	【必传】商户订单号，可以是时间戳，不可重复
                // type	整数	【必传】微信支付传入1 支付宝支付传入2
                // price	小数	【必传】订单金额
                // sign	字符串	【必传】签名，计算方式为 md5(payId+param+type+price+通讯密钥)
                // param	字符串	【可选】传输参数，将会原样返回到异步和同步通知接口
                // isHtml	整数	【可选】传入1则自动跳转到支付页面，否则返回创建结果的json数据
                //notifyUrl https://www.halo.run/async/notify
                //returnUrl https://www.halo.run/async/return

                //用户邮箱的md5前8位+年月日时分秒毫秒
                String payId = getCurrentTimestamp()+md5(spec.getEmail()).substring(0, 8);
                int type = (queryParams!=null && (!CollectionUtils.isEmpty(queryParams.get("type"))))?Integer.valueOf(queryParams.get("type").get(0)):2;

                //
                String param = null;
                try {
                    param = URLEncoder.encode(user.getMetadata().getName(), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    return Mono.error(new UnsupportedEncodingException(user.getMetadata().getName()));
                }
                final String finalParam = param;
                return environmentFetcher.fetchVmqSetting().flatMap(vmqSetting -> {
                    //vmq key
                    String key = vmqSetting.getVmqKey();
                    double price = vmqSetting.getVmqPrice();

                    String sign = md5(payId + finalParam + type + price + key);
                    // 返回 success 字符串或查询参数（按需求修改）

                    Map<String,String> result = Map.of(
                        "payId",payId,
                        "type",String.valueOf(type),
                        "price",String.valueOf(price),
                        "sign",sign,
                        "param",finalParam);
                    return ServerResponse.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(result);
                });
            })
            ;
    }


    private Mono<ServerResponse> vmqAsyncPublic(ServerRequest request) {

        return environmentFetcher.fetchVmqSetting().flatMap(vmqSetting -> {
            // 获取所有的 GET 参数
            // http://localhost:8090/apis/api.halo.run/v1alpha1/async/notify?payId=testPayOrder01&param=zlytest&type=2&price=0.1&reallyPrice=0.1&sign=9e0cd7b98a846588699e1a3ff20f993b
            var queryParams = request.queryParams();
            //
            log.info("=======vmq request queryParams:{}=========",queryParams);
            String payId = queryParams.getFirst("payId");
            String param = queryParams.getFirst("param");
            String type = queryParams.getFirst("type");
            String price = queryParams.getFirst("price");
            String reallyPrice = queryParams.getFirst("reallyPrice");
            String oldSign = queryParams.getFirst("sign");

            log.info("=======vmq request payId:{}=========",payId);
            //校验签名，计算方式 = md5(payId + param + type + price + reallyPrice + 通讯密钥)
            String sign = md5(payId + param + type + price + reallyPrice + vmqSetting.getVmqKey());
            if (!StringUtils.equals(sign, oldSign)) {
                return ServerResponse.badRequest().bodyValue("签名错误!");
            }
            log.info("=======vmq request start grant=========");
            return userService.getUser(param).flatMap(user -> {
                log.info("=======vmq request user:{}=========",user);
                return environmentFetcher.fetchComment().flatMap(comment -> {
                    log.info("=======vmq request comment:{}=========",comment);
                    return userService.grantRoles(param, Set.of(comment.getAllowCommentRole()))
                        .flatMap(u -> {
                            if (u == null) {
                                return ServerResponse.badRequest().bodyValue("用户不存在!");
                            }
                            return ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue("success"); // 或者直接返回 "success"
                        });
                });
            });
        });
    }

    public static String md5(String text) {
        //加密后的字符串
        String encodeStr= DigestUtils.md5DigestAsHex(text.getBytes());
        return encodeStr;
    }


    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.halo.run", "v1alpha1");
    }
}
