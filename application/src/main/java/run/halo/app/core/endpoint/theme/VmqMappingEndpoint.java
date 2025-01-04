package run.halo.app.core.endpoint.theme;

import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.theme.finders.SiteStatsFinder;
import run.halo.app.theme.finders.vo.SiteStatsVo;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;

/**
 * Endpoint for site stats query APIs.
 *
 * @author guqing
 * @since 2.5.0
 */
@Component
@RequiredArgsConstructor
public class VmqMappingEndpoint implements CustomEndpoint {

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
            )
            .build();
    }
    private Mono<ServerResponse> getCreateOrderSign(ServerRequest request) {

        //fixme zly: 校验用户登录

        //fixme zly: 校验用户是否为vip,如果已经是vip,则返回错误信息

        //fixme zly: 获取用户信息

        //fixme zly: 获取通讯key

        //fixme zly: 获取付款方式 和 付款金额(金额要后端计算)

        //payId	字符串	【必传】商户订单号，可以是时间戳，不可重复
        // type	整数	【必传】微信支付传入1 支付宝支付传入2
        // price	小数	【必传】订单金额
        // sign	字符串	【必传】签名，计算方式为 md5(payId+param+type+price+通讯密钥)
        // param	字符串	【可选】传输参数，将会原样返回到异步和同步通知接口
        // isHtml	整数	【可选】传入1则自动跳转到支付页面，否则返回创建结果的json数据
        String key = "73e95afb9b9a8ad4383f44f6553f62fd";
        String payId = "testPayOrder01";
        int type = 2;
        double price = 0.1;
        String param = "user01";
        int isHtml = 1;

        String sign = md5(payId + param + type + price + key);

        // 返回 success 字符串或查询参数（按需求修改）
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(sign);
    }


    private Mono<ServerResponse> vmqAsyncPublic(ServerRequest request) {
        //fixme zly 校验签名

        // 获取所有的 GET 参数
        // http://localhost:8090/?payId=testPayOrder01&param=user01&type=2&price=0.1&reallyPrice=0.1&sign=ce5e65097e97fe56cf224708ddc95415
        var queryParams = request.queryParams();
        queryParams.forEach((key, values) -> {
            System.out.println("=====get vmq: Key: " + key + ", Values: " + values);
        });
        //fixme zly: 修改用户为vip, 修改vip时间

        // 返回 success 字符串或查询参数（按需求修改）
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("success"); // 或者直接返回 "success"
    }
    //fixme zly: 增加return 页面, 重复get vmq订单状态, 如果充值成功, 但是会员没到账, 则设置会员信息.如果充值失败, 则返回失败信息, 如果充值成功, 会员到账, 则返回成功信息.

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
