package ch.bergturbenthal.raoa.viewer.interfaces.graphql;

import ch.bergturbenthal.raoa.elastic.model.AlbumData;
import ch.bergturbenthal.raoa.elastic.model.AlbumEntryData;
import ch.bergturbenthal.raoa.elastic.service.DataViewService;
import ch.bergturbenthal.raoa.libs.service.AlbumList;
import ch.bergturbenthal.raoa.libs.service.GitAccess;
import ch.bergturbenthal.raoa.viewer.model.graphql.Album;
import ch.bergturbenthal.raoa.viewer.model.graphql.AlbumEntry;
import ch.bergturbenthal.raoa.viewer.model.graphql.GroupReference;
import ch.bergturbenthal.raoa.viewer.model.graphql.KeywordCount;
import ch.bergturbenthal.raoa.viewer.model.graphql.LabelValue;
import ch.bergturbenthal.raoa.viewer.model.graphql.QueryContext;
import ch.bergturbenthal.raoa.viewer.model.graphql.UserReference;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.jetbrains.annotations.NotNull;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

@Slf4j
@Controller
public class AlbumQuery {
    public static final Pattern PATH_SPLIT = Pattern.compile(Pattern.quote("/"));
    public static final String TYPE_NAME = "Album";
    private final DataViewService dataViewService;
    private final AlbumList albumList;

    public AlbumQuery(final DataViewService dataViewService, final AlbumList albumList) {
        this.dataViewService = dataViewService;
        this.albumList = albumList;
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public String zipDownloadUri(Album album) {
        return album.getContext().getContexRootPath() + "/rest/album-zip/" + album.getId().toString();
    }

    // TODO: resolve also groups
    @SchemaMapping(typeName = TYPE_NAME)
    public Flux<UserReference> canAccessedBy(Album album) {
        if (album.getContext().canUserManageUsers()) {
            return dataViewService.listUserForAlbum(album.getId())
                    .map(u -> new UserReference(u.getId(), u.getUserData(), album.getContext()));
        }
        return Flux.empty();
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Flux<UserReference> canAccessedByUser(Album album) {
        if (album.getContext().canUserManageUsers()) {
            return dataViewService.listUserForAlbum(album.getId())
                    .map(u -> new UserReference(u.getId(), u.getUserData(), album.getContext()));
        }
        return Flux.empty();
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Flux<AlbumEntry> entries(Album album) {
        return dataViewService.listEntries(album.getId()).map(e -> createAlbumEntry(album, e))
                .doOnError(ex -> log.warn("Cannot load entries", ex));
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Mono<AlbumEntry> albumEntry(Album album, @Argument String entryId) {
        return dataViewService.loadEntry(album.getId(), ObjectId.fromString(entryId))
                .map(e -> createAlbumEntry(album, e));
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Mono<AlbumEntry> titleEntry(Album album) {
        return dataViewService.readAlbum(album.getId()).flatMap(a -> Mono.justOrEmpty(a.getTitleEntryId()))
                .flatMap(e -> dataViewService.loadEntry(album.getId(), e)).map(e -> createAlbumEntry(album, e));
    }

    @NotNull
    private AlbumEntry createAlbumEntry(final Album album, AlbumEntryData entry) {
        return new AlbumEntry(album, entry.getEntryId().name(), entry);
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Mono<String> name(Album album) {
        return extractElField(album, AlbumData::getName);
    }

    private <T> Mono<T> extractElField(final Album album, final Function<AlbumData, T> extractor) {
        return album.getElAlbumData().flatMap(d -> Mono.justOrEmpty(extractor.apply(d)));
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Mono<Integer> entryCount(Album album) {
        return extractElField(album, AlbumData::getEntryCount);
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Mono<String> version(Album album) {
        return extractElField(album, albumData -> albumData.getCurrentVersion().name());
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Mono<OffsetDateTime> albumTime(Album album) {
        return extractElField(album, AlbumData::getCreateTime).map(i -> i.atOffset(ZoneOffset.UTC));
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Flux<GroupReference> canAccessedByGroup(Album album) {
        final QueryContext context = album.getContext();
        return dataViewService.listGroups().filter(g -> context.canAccessGroup(g.getId()))
                .filter(g -> g.getVisibleAlbums().contains(album.getId()))
                .map(group -> new GroupReference(group.getId(), context, Mono.just(group)));
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Flux<LabelValue> labels(Album album) {
        return album.getElAlbumData().map(d -> Optional.ofNullable(d.getLabels())).filter(Optional::isPresent)
                .map(Optional::get).flatMapIterable(Map::entrySet).map(e -> new LabelValue(e.getKey(), e.getValue()));
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Flux<KeywordCount> keywordCounts(Album album) {
        return album.getElAlbumData().flatMapIterable(AlbumData::getKeywordCount)
                .map(k -> new KeywordCount(k.getKeyword(), k.getEntryCount()));
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Flux<OffsetDateTime> autoaddDates(Album album) {
        return albumList.getAlbum(album.getId()).flatMapMany(GitAccess::readAutoadd)
                .map(i -> i.atOffset(ZoneOffset.UTC));
    }

    @SchemaMapping(typeName = TYPE_NAME)
    public Mono<List<String>> albumPath(Album album) {
        return albumList.getAlbum(album.getId()).flatMap(GitAccess::getFullPath).map(PATH_SPLIT::split)
                .map(Arrays::asList).defaultIfEmpty(Collections.emptyList());
    }
}
