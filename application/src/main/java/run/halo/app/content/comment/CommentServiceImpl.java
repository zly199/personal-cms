package run.halo.app.content.comment;

import static run.halo.app.extension.index.query.QueryFactory.and;
import static run.halo.app.extension.index.query.QueryFactory.equal;
import static run.halo.app.extension.index.query.QueryFactory.isNull;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.content.PostContentService;
import run.halo.app.content.PostService;
import run.halo.app.core.counter.CounterService;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.core.user.service.RoleService;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.extension.router.selector.FieldSelector;
import run.halo.app.infra.SystemConfigurableEnvironmentFetcher;
import run.halo.app.infra.SystemSetting;
import run.halo.app.infra.exception.AccessDeniedException;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;
import run.halo.app.security.authorization.AuthorityUtils;

/**
 * Comment service implementation.
 *
 * @author guqing
 * @since 2.0.0
 */
@Component
public class CommentServiceImpl extends AbstractCommentService implements CommentService {

    private final ExtensionGetter extensionGetter;
    private final SystemConfigurableEnvironmentFetcher environmentFetcher;
    @Autowired
    private PostContentService postContentService;
    @Autowired
    private PostService postService;

    public CommentServiceImpl(RoleService roleService, ReactiveExtensionClient client,
        UserService userService, CounterService counterService, ExtensionGetter extensionGetter,
        SystemConfigurableEnvironmentFetcher environmentFetcher) {
        super(roleService, client, userService, counterService);
        this.extensionGetter = extensionGetter;
        this.environmentFetcher = environmentFetcher;
    }

    @Override
    public Mono<ListResult<ListedComment>> listComment(CommentQuery commentQuery) {
        return this.client.listBy(Comment.class, commentQuery.toListOptions(),
                commentQuery.toPageRequest())
            .flatMap(comments -> Flux.fromStream(comments.get()
                    .map(this::toListedComment))
                .flatMapSequential(Function.identity())
                .collectList()
                .map(list -> new ListResult<>(comments.getPage(), comments.getSize(),
                    comments.getTotal(), list)
                )
            );
    }

    @Override
    public Mono<Comment> create(Comment comment) {
        return environmentFetcher.fetchComment()
            .flatMap(commentSetting -> {
                if (Boolean.FALSE.equals(commentSetting.getEnable())) {
                    return Mono.error(
                        new AccessDeniedException("The comment function has been turned off.",
                            "problemDetail.comment.turnedOff", null));
                }
                if (checkCommentOwner(comment, commentSetting.getSystemUserOnly())) {
                    return Mono.error(
                        new AccessDeniedException("Allow only system users to comment.",
                            "vip.comment.errorMsg", null));
                }

                if (comment.getSpec().getTop() == null) {
                    comment.getSpec().setTop(false);
                }
                if (comment.getSpec().getPriority() == null) {
                    comment.getSpec().setPriority(0);
                }
                comment.getSpec()
                    .setApproved(Boolean.FALSE.equals(commentSetting.getRequireReviewForNew()));

                if (BooleanUtils.isTrue(comment.getSpec().getApproved())
                    && comment.getSpec().getApprovedTime() == null) {
                    comment.getSpec().setApprovedTime(Instant.now());
                }

                if (comment.getSpec().getCreationTime() == null) {
                    comment.getSpec().setCreationTime(Instant.now());
                }

                comment.getSpec().setHidden(false);

                if (commentSetting.getLimitCommentRange() != null
                    && commentSetting.getLimitCommentRange()) {
                    return hasCommentPermission(comment, commentSetting)
                        .flatMap(hasPermission -> {
                            if (!hasPermission) {
                                return Mono.error(
                                    new AccessDeniedException("Allow only system users to comment.",
                                        "vip.comment.errorMsg", null));
                            }
                            return Mono.just(comment); // 权限检查通过，继续返回评论
                        });
                }
                return Mono.just(comment);
            })
            .flatMap(populatedComment -> Mono.when(populateOwner(populatedComment),
                    populateApproveState(populatedComment))
                .thenReturn(populatedComment)
            ).flatMap(comment1 -> {
                if (comment.getSpec() != null && comment.getSpec().getSubjectRef() != null
                    && comment.getSpec().getSubjectRef().getKind().equals("SinglePage")) {
                    return Mono.just(comment1);
                }
                return environmentFetcher.fetchComment()
                    .flatMap(commentSetting -> {
                        Integer limitCount = commentSetting.getLimitCommentCount();
                        if (limitCount == null || limitCount <= 0) {
                            return Mono.just(comment1);
                        }
                        // 查询当天评论次数
                        return countUserCommentsToday(comment.getSpec().getOwner().getName())
                            .flatMap(artileIds -> {
                                if (artileIds.size() >= limitCount) {
                                    return Mono.error(new AccessDeniedException(
                                        "Comment limit exceeded for today.",
                                        "vip.comment.limitExceeded", null));
                                }
                                return Mono.just(comment1);
                            });
                        });
            })
            .flatMap(client::create);
    }

    private Mono<Set<String>> countUserCommentsToday(String userName) {
        LocalDate today = LocalDate.now();
        Instant startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        return client.listAll(Comment.class, new ListOptions(), Sort.by("metadata.creationTimestamp"))
            .filter(comment -> {
                //must be post comment
                if (comment.getSpec() != null && comment.getSpec().getSubjectRef() != null
                        && comment.getSpec().getSubjectRef().getKind().equals("SinglePage")) {
                    return false;
                }
                return true;
            })
            .filter(comment -> {
                Comment.CommentOwner owner = comment.getSpec().getOwner();
                return owner != null && owner.getName().equals(userName);
            })
            .filter(comment -> {
                Instant approvedTime = comment.getSpec().getApprovedTime();
                return approvedTime != null && !approvedTime.isBefore(startOfDay) && !approvedTime.isAfter(endOfDay);
            })
            .map(comment -> comment.getSpec().getSubjectRef().getName()) // 提取文章 ID
            .collect(Collectors.toSet()); // 收集为 Set<String>
    }

    Mono<Boolean> hasCommentPermission(Comment comment, SystemSetting.Comment commentSetting) {

        return ReactiveSecurityContextHolder.getContext()
            .flatMap(securityContext -> {
                if (StringUtils.isBlank(commentSetting.getAllowCommentRole())){
                    return Mono.just(false);
                }
                var authentication = securityContext.getAuthentication();
                var roles = AuthorityUtils.authoritiesToRoles(authentication.getAuthorities());
                return roleService.contains(roles,
                    Set.of(commentSetting.getAllowCommentRole()));
            }).flatMap( permissionRole -> {
                    if (StringUtils.isBlank(commentSetting.getAllowCommentTag())){
                        return Mono.just(false);
                    }
                    if (comment.getSpec() == null
                        || comment.getSpec().getSubjectRef() == null
                        || StringUtils.isBlank(comment.getSpec().getSubjectRef().getName())){
                        return Mono.error(
                            new AccessDeniedException("文章不存在"));
                    }
                    //只限制文章
                    if (!StringUtils.equals(comment.getSpec().getSubjectRef().getKind(),"Post")){
                        return Mono.just(true);
                    }
                    String postId = comment.getSpec().getSubjectRef().getName();
                    return client.get(Post.class, postId)
                        .flatMap(post ->{
                            if (post == null){
                                return Mono.error(
                                    new AccessDeniedException("文章不存在"));
                            }
                            if (post.getSpec()!=null
                                    && post.getSpec().getTags()!=null
                                    && post.getSpec().getTags().contains(commentSetting.getAllowCommentTag())){
                                // 免费分类
                                return Mono.just(permissionRole || true);
                            }
                            return Mono.just(permissionRole || false);
                        });
                }
            );
    }

    private Mono<Void> populateApproveState(Comment comment) {
        return hasCommentManagePermission()
            .filter(Boolean::booleanValue)
            .doOnNext(hasPermission -> {
                comment.getSpec().setApproved(true);
                comment.getSpec().setApprovedTime(Instant.now());
            })
            .then();
    }

    Mono<Void> populateOwner(Comment comment) {
        if (comment.getSpec().getOwner() != null) {
            return Mono.empty();
        }
        return fetchCurrentUser()
            .switchIfEmpty(Mono.error(new IllegalStateException("The owner must not be null.")))
            .map(this::toCommentOwner)
            .doOnNext(owner -> comment.getSpec().setOwner(owner))
            .then();
    }

    @Override
    public Mono<Void> removeBySubject(@NonNull Ref subjectRef) {
        Assert.notNull(subjectRef, "The subjectRef must not be null.");
        return cleanupComments(subjectRef, 200);
    }

    private Mono<Void> cleanupComments(Ref subjectRef, int batchSize) {
        // ascending order by creation time and name
        final var pageRequest = PageRequestImpl.of(1, batchSize,
            Sort.by("metadata.creationTimestamp", "metadata.name"));
        // forever loop first page until no more to delete
        return listCommentsByRef(subjectRef, pageRequest)
            .flatMap(page -> Flux.fromIterable(page.getItems())
                .flatMap(this::deleteWithRetry)
                .then(page.hasNext() ? cleanupComments(subjectRef, batchSize) : Mono.empty())
            );
    }

    private Mono<Comment> deleteWithRetry(Comment item) {
        return client.delete(item)
            .onErrorResume(OptimisticLockingFailureException.class,
                e -> attemptToDelete(item.getMetadata().getName()));
    }

    private Mono<Comment> attemptToDelete(String name) {
        return Mono.defer(() -> client.fetch(Comment.class, name)
                .flatMap(client::delete)
            )
            .retryWhen(Retry.backoff(8, Duration.ofMillis(100))
                .filter(OptimisticLockingFailureException.class::isInstance));
    }

    Mono<ListResult<Comment>> listCommentsByRef(Ref subjectRef, PageRequest pageRequest) {
        var listOptions = new ListOptions();
        listOptions.setFieldSelector(FieldSelector.of(
            and(equal("spec.subjectRef", Comment.toSubjectRefKey(subjectRef)),
                isNull("metadata.deletionTimestamp"))
        ));
        return client.listBy(Comment.class, listOptions, pageRequest);
    }

    private boolean checkCommentOwner(Comment comment, Boolean onlySystemUser) {
        Comment.CommentOwner owner = comment.getSpec().getOwner();
        if (Boolean.TRUE.equals(onlySystemUser)) {
            return owner != null && Comment.CommentOwner.KIND_EMAIL.equals(owner.getKind());
        }
        return false;
    }

    private Mono<ListedComment> toListedComment(Comment comment) {
        var builder = ListedComment.builder().comment(comment);
        // not empty
        var ownerInfoMono = getOwnerInfo(comment.getSpec().getOwner())
            .doOnNext(builder::owner);
        var subjectMono = getCommentSubject(comment.getSpec().getSubjectRef())
            .doOnNext(builder::subject);
        var statsMono = fetchCommentStats(comment.getMetadata().getName())
            .doOnNext(builder::stats);
        return Mono.when(ownerInfoMono, subjectMono, statsMono)
            .then(Mono.fromSupplier(builder::build));
    }

    @SuppressWarnings("unchecked")
    Mono<Extension> getCommentSubject(Ref ref) {
        return extensionGetter.getExtensions(CommentSubject.class)
            .filter(subject -> subject.supports(ref))
            .next()
            .flatMap(subject -> subject.get(ref.getName()));
    }
}
