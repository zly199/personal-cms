package run.halo.app.core.endpoint.theme;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.infra.actuator.GlobalInfoService;
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
public class OtherEndpoint implements CustomEndpoint {


    private final GlobalInfoService globalInfoService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        var tag = "contactMe";
        return SpringdocRouteBuilder.route()
            .GET("other/logo-url", this::getLogoUrl,
                builder -> builder.operationId("getLogoUrl")
                    .description("Get the logo url of the site.")
                    .tag(tag)
                    .response(responseBuilder()
                        .implementation(String.class)
                    )
            )
            .build();
    }

    private Mono<ServerResponse> getLogoUrl(ServerRequest request) {
        return globalInfoService.getGlobalInfo().cache()
            .flatMap(globalInfo -> ServerResponse.ok().bodyValue(globalInfo.getSiteLogoUrl()));
    }



    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.halo.run", "v1alpha1");
    }
}
