import {Injectable, Predicate} from '@angular/core';
import {
    Album,
    AlbumContentGQL,
    AlbumContentQuery,
    AlbumContentQueryVariables,
    AlbumEntry,
    AllAlbumVersionsGQL,
    AllAlbumVersionsQuery,
    AllAlbumVersionsQueryVariables,
    GetAlbumDetailsGQL,
    GetAlbumDetailsQuery,
    GetAlbumDetailsQueryVariables,
    Label,
    MutationData,
    SingleAlbumMutateGQL,
    SingleAlbumMutateMutation,
    SingleAlbumMutateMutationVariables,
    UserPermissionsGQL,
    UserPermissionsQuery,
    UserPermissionsQueryVariables
} from '../generated/graphql';
import {ServerApiService} from './server-api.service';
import {Router} from '@angular/router';
import {
    AlbumData,
    AlbumEntryData,
    AlbumSettings,
    createUrlFromBlob,
    DiashowEntry,
    ImageBlob,
    ImageUrl,
    KeywordState,
    StorageService
} from './storage.service';
import {FNCH_COMPETITION_ID} from '../constants';
import {HttpClient} from '@angular/common/http';
import {LoginService} from './login.service';
import {fromEventPattern, merge, Observable, Subscriber} from 'rxjs';
import {map, share} from 'rxjs/operators';
import {DomSanitizer, SafeResourceUrl, SafeUrl} from '@angular/platform-browser';
import {LRUCache} from 'lru-cache';

const IMAGE_CACHE_NAME = 'image-cache';
const VIDEO_CACHE_NAME = 'video-cache';

export type QueryAlbumEntry =
    { __typename?: 'AlbumEntry' }
    & Pick<AlbumEntry, 'id' | 'name' | 'entryUri' | 'targetWidth' | 'targetHeight' | 'created' | 'keywords'>;

export interface UserPermissions {
    canManageUsers: boolean;
    canEdit: boolean;
    isRegistered: boolean;
}

export interface SyncProgress {
    albumCount: number;
    albumIndex: number;
    albumEntryCount: number;
    albumEntryIndex: number;
    albumName: string;
}

function createStoreEntry(albumEntry: { __typename?: 'AlbumEntry' } &
                              Pick<AlbumEntry,
                                  'id' |
                                  'name' |
                                  'entryUri' |
                                  'targetWidth' |
                                  'targetHeight' |
                                  'created' |
                                  'contentType' |
                                  'keywords' |
                                  'cameraModel' |
                                  'exposureTime' |
                                  'fNumber' |
                                  'focalLength35' |
                                  'isoSpeedRatings'>,
                          albumId: string): AlbumEntryData {
    return {
        albumEntryId: albumEntry.id,
        albumId,
        created: Date.parse(albumEntry.created),
        entryType: (albumEntry.contentType.startsWith('video') || albumEntry.contentType === 'application/x-matroska') ? 'video' : 'image',
        keywords: albumEntry.keywords,
        name: albumEntry.name,
        targetHeight: albumEntry.targetHeight,
        targetWidth: albumEntry.targetWidth,
        cameraModel: albumEntry.cameraModel,
        exposureTime: albumEntry.exposureTime,
        fNumber: albumEntry.fNumber,
        focalLength35: albumEntry.focalLength35,
        isoSpeedRatings: albumEntry.isoSpeedRatings,
        contentType: albumEntry.contentType
    };
}


function createStoreAlbum(album: {
    __typename?: 'Album'
} & Pick<Album, 'id' | 'name' | 'entryCount' | 'albumTime' | 'version'> &
    { labels: Array<{ __typename?: 'Label' } & Pick<Label, 'labelName' | 'labelValue'>> }): AlbumData {
    return {
        albumTime: Date.parse(album.albumTime),
        entryCount: album.entryCount,
        albumVersion: album.version,
        title: album.name,
        fnchAlbumId: album.labels.filter(e => e.labelName === FNCH_COMPETITION_ID).map(e => e.labelValue).shift(),
        id: album.id
    };
}

export function createFilter(
    filteringKeywords: string[],
    keywordCombine: 'and' | 'or',
    filteringTimeRange: [number, number],
    showPhotos: boolean,
    showVideos: boolean):
    (AlbumEntryData) => boolean {
    const filters: ((entry: AlbumEntryData) => boolean)[] = [];
    if (filteringKeywords.length > 0) {
        if (keywordCombine === 'and') {
            filters.push((entry: AlbumEntryData) =>
                filteringKeywords
                    .filter(fkw => entry.keywords.find(kw => kw === fkw) !== undefined)
                    .length === filteringKeywords.length);
        } else {
            const filterKeywords = new Set<string>();
            filteringKeywords.forEach(k => filterKeywords.add(k));
            filters.push((entry: AlbumEntryData) => entry.keywords.find(k => filterKeywords.has(k)) !== undefined);
        }
    }
    if (filteringTimeRange !== undefined) {
        const filterFrom = filteringTimeRange[0];
        const filterUntil = filteringTimeRange[1];
        filters.push((entry: AlbumEntryData) => entry.created >= filterFrom && entry.created < filterUntil);
    }
    if (!showPhotos) {
        filters.push((entry: AlbumEntryData) => entry.entryType !== 'image');
    }
    if (!showVideos) {
        filters.push((entry: AlbumEntryData) => entry.entryType !== 'video');
    }
    if (filters.length === 0) {
        return () => true;
    }
    if (filters.length === 1) {
        return filters[0];
    }
    return entry => filters.find(f => f(entry)) !== undefined;
}

export function filterTimeResolution(albumEntries: AlbumEntryData[], timeResolution: number): AlbumEntryData[] {
    if (timeResolution > 0) {
        const sortedEntries: AlbumEntryData[] = [];
        let lastTime = Number.MIN_SAFE_INTEGER;
        albumEntries.forEach(entry => {
            if (entry.created - lastTime > timeResolution) {
                lastTime = entry.created;
                sortedEntries.push(entry);
            }
        });
        return sortedEntries;
    } else {
        return albumEntries;
    }
}


function sortKey(data: MutationData) {
    const addKeywordMutation = data.addKeywordMutation;
    if (addKeywordMutation) {
        return addKeywordMutation.albumId + ':' + addKeywordMutation.albumEntryId + ':' + addKeywordMutation.keyword;
    }
    const removeKeywordMutation = data.removeKeywordMutation;
    if (removeKeywordMutation) {
        return removeKeywordMutation.albumId + ':' + removeKeywordMutation.albumEntryId + ':' + removeKeywordMutation.keyword;
    }
    return '';
}

function imageUrl(albumId: string, albumEntryId: string, nextStepMaxLength: number) {
    return '/rest/album/' + albumId + '/' + albumEntryId + '/thumbnail?maxLength=' + nextStepMaxLength;
}

function videoUrl(albumId: string, albumEntryId: string, nextStepMaxLength: number) {
    return '/rest/album/' + albumId + '/' + albumEntryId + '/videothumbnail?maxLength=' + nextStepMaxLength;
}


function createMapKey(albumId: string, albumEntryId: string, length: number, video: boolean): string {
    if (video) {
        return `${albumId}-${albumEntryId}-${length}-v`;
    } else {
        return `${albumId}-${albumEntryId}-${length}`;
    }
}

@Injectable({
    providedIn: 'root'
})
export class DataService {// implements OnDestroy {
    private syncRunning = false;
    private syncStateId = 0;
    public readonly albumModified: Observable<string>;
    public readonly onlineState: Observable<boolean>;
    private mutationSubscribe: Subscriber<string>;
    private imageCache: Promise<Cache>;
    private videoCache: Promise<Cache>;
    private loadedBlobs: LRUCache<string, ImageUrl> = new LRUCache({
        max: 2000,
        maxSize: 100 * 1024 * 1024,
        sizeCalculation: (value, key) => value.fileSize,
        dispose: (value, key) => URL.revokeObjectURL(value.url)
    });
    private pendingBlobLoad = new Map<string, Promise<SafeResourceUrl>>();

    constructor(private serverApi: ServerApiService,
                private loginService: LoginService,
                private albumContentGQL: AlbumContentGQL,
                private allAlbumVersionsGQL: AllAlbumVersionsGQL,
                private getAlbumDetailsGQL: GetAlbumDetailsGQL,
                private userPermissionsGQL: UserPermissionsGQL,
                // private subscribeAlbumMutationsGQL: SubscribeAlbumMutationsGQL,
                private router: Router,
                private singleAlbumMutateGQL: SingleAlbumMutateGQL,
                private storageService: StorageService,
                private http: HttpClient, private sanitizer: DomSanitizer) {
        this.albumModified = new Observable<string>(subscribe => {
            this.mutationSubscribe = subscribe;
        }).pipe(share());

        const onlineObservable = fromEventPattern(
            handler => window.addEventListener('online', handler),
            handler => window.removeEventListener('online', handler))
            .pipe(map(value => true));
        const offlineObservable = fromEventPattern(
            handler => window.addEventListener('offline', handler),
            handler => window.removeEventListener('offline', handler))
            .pipe(map(value => false));
        this.onlineState = merge(onlineObservable, offlineObservable).pipe(share());
        loginService.loginObservable.subscribe(loginData => {
            this.updateAlbumData().then();
        });
        this.initCache();
    }

    private initCache() {
        this.imageCache = caches.open(IMAGE_CACHE_NAME).then(cache => {
            this.imageCache = Promise.resolve(cache);
            return cache;
        });
        this.videoCache = caches.open(VIDEO_CACHE_NAME).then(cache => {
            this.videoCache = Promise.resolve(cache);
            return cache;
        });
    }

    async updateAlbumData() {

        /*this.serverApi
            .subscribe(this.subscribeAlbumMutationsGQL, {})
            .subscribe(album => console.log(album));
*/
        const [albumVersionList, storedAlbumEnties] = await
            Promise.all([this.serverApi.query<AllAlbumVersionsQuery, AllAlbumVersionsQueryVariables>(
                this.allAlbumVersionsGQL, {}
            ),
                this.storageService.listAlbums()]);
        if (!albumVersionList) {
            return;
        }
        const storeAlbumsVersions = new Map<string, string>();
        storedAlbumEnties.forEach(entry => storeAlbumsVersions.set(entry[0].id, entry[0].albumVersion));
        let albumDataBatch: Promise<AlbumData>[] = [];
        let lastStore: Promise<void> = Promise.resolve();
        const keepAlbums: string[] = [];
        for (const albumVersion of albumVersionList.listAlbums) {
            const albumId = albumVersion.id;
            keepAlbums.push(albumId);
            const latestVersion = albumVersion.version;
            if (storeAlbumsVersions.get(albumId) === latestVersion) {
                // already up to date
                continue;
            }
            if (albumDataBatch.length > 20) {
                await lastStore;
                lastStore = Promise.all(albumDataBatch).then(async fetchedAlbums => {
                    await this.storageService.updateAlbums(fetchedAlbums);
                    fetchedAlbums.forEach(album => this.notifyAlbumUpdated(album.id));
                });
                albumDataBatch = [];
            }
            albumDataBatch.push(this.serverApi.query<GetAlbumDetailsQuery, GetAlbumDetailsQueryVariables>(
                this.getAlbumDetailsGQL, {albumId}
            ).then(v => createStoreAlbum(v.albumById)));
        }
        await Promise.all(albumDataBatch).then(async fetchedAlbums => {
            await this.storageService.updateAlbums(fetchedAlbums);
            fetchedAlbums.forEach(album => this.notifyAlbumUpdated(album.id));
        });
        const removedAlbums = await this.storageService.keepAlbums(keepAlbums);
        if (removedAlbums.length > 0) {
            removedAlbums.forEach(albumId => this.notifyAlbumUpdated(albumId));
        }
    }


    public synchronizeData(): Observable<SyncProgress> {
        return new Observable<SyncProgress>(subscriber => {
            const runner = async () => {
                if (this.syncRunning) {
                    return;
                }
                try {
                    this.syncRunning = true;
                    await this.updateAlbumData();
                    if (subscriber.closed) {
                        return;
                    }
                    const deviceData = await this.storageService.getDeviceData();
                    const screenSize = deviceData?.screenSize || 3200;
                    const smallSize = Math.max(screenSize / 8, 25);
                    const albumsToUpdate = await this.storageService.findDeprecatedAlbums();
                    subscriber.next({
                        albumCount: albumsToUpdate.length,
                        albumEntryCount: 0,
                        albumEntryIndex: 0,
                        albumIndex: 0,
                        albumName: ''
                    });
                    for (let i = 0; i < albumsToUpdate.length && !subscriber.closed; i++) {
                        const album = albumsToUpdate[i];
                        const fetchedAlbum = await this.fetchAlbum(album);
                        subscriber.next({
                            albumCount: albumsToUpdate.length,
                            albumEntryCount: 0,
                            albumEntryIndex: 0,
                            albumIndex: i,
                            albumName: fetchedAlbum[0].title
                        });
                        this.notifyAlbumUpdated(album);
                    }
                    if (subscriber.closed) {
                        return;
                    }
                    const albumsToSync = await this.storageService.findAlbumsToSync();
                    if (subscriber.closed) {
                        return;
                    }
                    for (let i = 0; i < albumsToSync.length && !subscriber.closed; i++) {
                        const album = albumsToSync[i];
                        const albumSettings = album[0];
                        const albumData = album[1];
                        subscriber.next({
                            albumCount: albumsToSync.length,
                            albumEntryCount: 0,
                            albumEntryIndex: 0,
                            albumIndex: i,
                            albumName: albumData.title
                        });
                        const [, entries,] = await this.storageService.listAlbum(albumSettings.id);
                        let batch: Promise<ImageBlob>[] = [];
                        let pendingStore: Promise<ImageBlob[]> = Promise.resolve([]);
                        const totalEntryCount = entries.length * 5;
                        let entryIndex = 0;
                        for (const entry of entries) {
                            if (subscriber.closed) {
                                return;
                            }
                            subscriber.next({
                                albumCount: albumsToSync.length,
                                albumEntryCount: totalEntryCount,
                                albumEntryIndex: entryIndex++,
                                albumIndex: i,
                                albumName: albumData.title
                            });
                            if (batch.length > 10) {
                                await pendingStore;
                                pendingStore = Promise.all(batch);
                                batch = [];
                            }
                            batch.push(this.fetchImage(albumSettings.id, entry.albumEntryId, smallSize, 1));
                            if (entry.entryType === 'video') {
                                batch.push(this.fetchVideo(albumSettings.id, entry.albumEntryId, smallSize, 1));
                            }
                        }
                        for (const entry of entries) {
                            if (subscriber.closed) {
                                return;
                            }
                            subscriber.next({
                                albumCount: albumsToSync.length,
                                albumEntryCount: totalEntryCount,
                                albumEntryIndex: entryIndex += 4,
                                albumIndex: i,
                                albumName: albumData.title
                            });
                            if (batch.length > 10) {
                                await pendingStore;
                                pendingStore = Promise.all(batch);
                                batch = [];
                            }
                            batch.push(this.fetchImage(albumSettings.id, entry.albumEntryId, screenSize, 1));
                            if (entry.entryType === 'video') {
                                batch.push(this.fetchVideo(albumSettings.id, entry.albumEntryId, screenSize, 1));
                            }
                        }
                        await pendingStore;
                        await Promise.all(batch);
                        await this.storageService.setOfflineSynced(albumSettings.id, albumSettings.albumEntryVersion);
                    }
                } finally {
                    subscriber.complete();
                    this.syncRunning = false;
                }
            };
            runner().then();
        });
    }

    private notifyAlbumUpdated(album: string) {
        this.mutationSubscribe?.next(album);
    }


    public async setSync(albumId: string, enabled: boolean) {
        await this.storageService.setSync(albumId, enabled);
        this.syncStateId += 1;
    }

    public async listAlbums(): Promise<[AlbumData, AlbumSettings | undefined][]> {
        return await this.storageService.listAlbums();
    }

    public async listAlbum(albumId: string, filter?: (AlbumEntryData) => boolean): Promise<[AlbumData, AlbumEntryData[], AlbumSettings]> {
        const [album, entries, albumSettings] = await this.storageService.listAlbum(albumId, filter);
        if (album === undefined || entries === undefined) {
            if (!navigator.onLine) {
                throw new Error('Offline');
            }
            try {
                await this.fetchAlbum(albumId);
            } catch (error) {
                await this.router.navigate(['/']);
            }
            return await this.listAlbum(albumId, filter);
        }
        return [album, entries, albumSettings];
    }

    private async fetchAlbum(albumId: string): Promise<[AlbumData, AlbumEntryData[]]> {
        // const oldAlbumState = await this.storageService.getAlbum(albumId);
        const content = await this.serverApi.query<AlbumContentQuery, AlbumContentQueryVariables>(this.albumContentGQL, {albumId});
        const albumById = content.albumById;
        if (!albumById) {
            // await this.router.navigate(['/']);
            throw new Error('insufficient permissions');
        }

        const newEntries: AlbumEntryData[] = [];
        albumById.entries.forEach(albumEntry => {
            newEntries.push(createStoreEntry(albumEntry, albumId));
        });
        const albumData = createStoreAlbum(albumById);
        await this.storageService.updateAlbums([albumData]);
        await this.storageService.updateAlbumEntries(newEntries, albumId, albumById.version);
        return [albumData, newEntries];
    }

    public async modifyAlbum(updates: MutationData[]): Promise<void> {
        const MUTATION_BATCH_SIZE = 50;
        for (let i = 0; i < updates.length; i += MUTATION_BATCH_SIZE) {
            const result = await this.serverApi.update<SingleAlbumMutateMutation, SingleAlbumMutateMutationVariables>(
                this.singleAlbumMutateGQL,
                {updates: updates.slice(i, Math.min(i + MUTATION_BATCH_SIZE, updates.length))});
            const mutate = result?.mutate;
            if (mutate) {
                /*if (mutate.errors && mutate.errors.length > 0) {
                    const messages = mutate.errors.map(m => m.message).join(', ');
                    /*const toastElement = await this.toastController.create({
                        message: 'Fehler beim Speichern' + messages + '"',
                        duration: 10000,
                        color: 'danger'
                    });
                    await toastElement.present();
                }*/
                if (mutate.modifiedEntries && mutate.modifiedEntries.length > 0) {
                    const modifiedEntries: AlbumEntryData[] = [];
                    mutate.modifiedEntries.forEach(entry => modifiedEntries.push(createStoreEntry(entry, entry.album.id)));
                    await this.storageService.updateAlbumEntries(modifiedEntries);
                }
            }
        }
    }


    public async userPermission(): Promise<UserPermissions> {

        const storedPermissions = await this.storageService.getUserPermissions();
        if (storedPermissions !== undefined) {
            if (!navigator.onLine) {
                return storedPermissions;
            }
            if (storedPermissions.lastLoaded !== undefined && storedPermissions.lastLoaded > Date.now() + 5 * 60 * 1000) {
                return storedPermissions;
            }
        }
        if (!navigator.onLine) {
            throw new Error('Offline');
        }
        if (!await this.loginService.hasValidToken()) {
            return undefined;
        }
        const data = await this.serverApi.query<UserPermissionsQuery, UserPermissionsQueryVariables>(this.userPermissionsGQL, {});
        if (!data) {
            return storedPermissions;
        }
        const ret = {
            canEdit: data.currentUser?.canEdit || false,
            canManageUsers: data.currentUser?.canManageUsers || false,
            isRegistered: Boolean(data.currentUser),
            lastLoaded: Date.now()
        };
        await this.storageService.setUserPermissions(ret);
        return ret;
    }

    public async addKeyword(albumId: string, selectedEntries: string[], keyword: string[]) {
        await this.storageService.addKeyword(albumId, selectedEntries, keyword);
        this.notifyAlbumUpdated(albumId);
    }

    public async removeKeyword(albumId: string, selectedEntries: string[], keyword: string[]) {
        await this.storageService.removeKeyword(albumId, selectedEntries, keyword);
        this.notifyAlbumUpdated(albumId);
    }

    public async currentKeywordStates(albumId: string, entries: Set<string>): Promise<Map<string, KeywordState>> {
        return await this.storageService.currentKeywordStates(albumId, entries);
    }

    async storeMutations(albumId: string) {
        if (!navigator.onLine) {
            return;
        }
        const mutations: MutationData[] = [];
        const touchedAlbums = new Set<string>();
        await Promise.all([
            this.storageService.pendingAddKeywords(albumId, pendingAddEntry => {
                mutations.push({
                    addKeywordMutation: {
                        albumId: pendingAddEntry.albumId,
                        keyword: pendingAddEntry.keyword,
                        albumEntryId: pendingAddEntry.albumEntryId
                    }
                });
                touchedAlbums.add(pendingAddEntry.albumId);
            }),
            this.storageService.pendingRemoveKeywords(albumId, pendingRemoveEntry => {
                mutations.push({
                    removeKeywordMutation: {
                        albumId: pendingRemoveEntry.albumId,
                        keyword: pendingRemoveEntry.keyword,
                        albumEntryId: pendingRemoveEntry.albumEntryId
                    }
                });
                touchedAlbums.add(pendingRemoveEntry.albumId);
            })
        ]);
        mutations.sort((a, b) => {
            return sortKey(a).localeCompare(sortKey(b));
        });
        if (mutations.length > 0) {
            await this.modifyAlbum(mutations);
            touchedAlbums.forEach(id => this.notifyAlbumUpdated(id));
            this.syncStateId += 1;
        }
    }

    public async getAlbumEntry(albumId: string, albumEntryId: string): Promise<[AlbumEntryData, Set<string>] | undefined> {
        const albumEntry = await this.storageService.getAlbumEntry(albumId, albumEntryId);
        if (albumEntry !== undefined) {
            return albumEntry;
        }
        await this.fetchAlbumIfMissing(albumId);
        return this.storageService.getAlbumEntry(albumId, albumEntryId);
    }

    public async countPendingMutations(albumId: string): Promise<number> {
        return this.storageService.countPendingMutations(albumId);
    }

    public async clearPendingMutations(albumId: string) {
        return this.storageService.clearPendingMutations(albumId);
    }

    public async getImage(albumId: string, albumEntryId: string, minSize: number): Promise<SafeUrl> {
        const maxShift = navigator.onLine ? 3 : 10;
        const existingBlob = this.findLoadedBlob(albumId, albumEntryId, minSize, maxShift, false);
        if (existingBlob) {
            return this.sanitizer.bypassSecurityTrustResourceUrl(existingBlob.url);
        }
        const fetchedImage = await this.fetchImage(albumId, albumEntryId, minSize, maxShift);
        const foundLength = fetchedImage.mediaSize;
        const createdUrl = createUrlFromBlob(fetchedImage);
        this.loadedBlobs.set(createMapKey(albumId, albumEntryId, foundLength, false), createdUrl);
        return this.sanitizer.bypassSecurityTrustResourceUrl(createdUrl.url);
    }

    private async fetchImage(albumId: string, albumEntryId: string, minSize: number, maxShift: number): Promise<ImageBlob> {
        // const startTime = Date.now();
        const cache = await this.imageCache;
        const nextStepMaxLength = findNextStep(minSize);
        for (let shift = 0; shift < maxShift; shift++) {
            // tslint:disable-next-line:no-bitwise
            const length = nextStepMaxLength * (1 << shift);
            if (length > 3200) {
                continue;
            }
            const candidateSrc = imageUrl(albumId, albumEntryId, length);
            const cacheEntry = await cache.match(candidateSrc);
            if (cacheEntry) {
                // console.log('cache lookup: ', shift, Date.now() - startTime);
                return {
                    albumId,
                    albumEntryId,
                    mediaSize: nextStepMaxLength,
                    data: await cacheEntry.blob()
                };
            }
        }
        if (!navigator.onLine) {
            throw new Error('Offline');
        }
        // console.log('cache test: ', Date.now() - startTime);
        const src = imageUrl(albumId, albumEntryId, nextStepMaxLength);

        for (let i = 0; i < 10; i++) {
            try {
                const imageBlob = await this.http.get(src, {responseType: 'blob'}).toPromise();
                await cache.put(src, new Response(imageBlob));
                if (!imageBlob.type.startsWith('image')) {
                    console.error(`wrong content type of ${albumEntryId}: ${imageBlob.type}`);
                    continue;
                }

                return {
                    albumId,
                    albumEntryId,
                    mediaSize: nextStepMaxLength,
                    data: imageBlob
                };
            } catch (error) {
                console.error(`Cannot load ${albumEntryId}, try ${i}`, error);
            }
        }
        throw new Error('Error fetching image');
    }

    public async getVideo(albumId: string, albumEntryId: string, minSize: number): Promise<SafeResourceUrl> {
        const pendingQueryKey = createMapKey(albumId, albumEntryId, minSize, true);
        const alreadyLoading = this.pendingBlobLoad.get(pendingQueryKey);
        if (alreadyLoading) {
            return alreadyLoading;
        }
        const promise = this.doGetVideo(albumId, albumEntryId, minSize);
        this.pendingBlobLoad.set(pendingQueryKey, promise);
        try {
            const safeResourceUrl = await promise;
            return safeResourceUrl;
        } finally {
            this.pendingBlobLoad.delete(pendingQueryKey);
        }
    }

    private async doGetVideo(albumId: string, albumEntryId: string, minSize: number) {
        const maxShift = navigator.onLine ? 3 : 10;
        const existingBlob = this.findLoadedBlob(albumId, albumEntryId, minSize, maxShift, true);
        if (existingBlob) {
            return this.sanitizer.bypassSecurityTrustResourceUrl(existingBlob.url);
        }
        const fetchedVideo = await this.fetchVideo(albumId, albumEntryId, minSize, maxShift);
        const foundLength = fetchedVideo.mediaSize;
        const createdUrl = createUrlFromBlob(fetchedVideo);
        this.loadedBlobs.set(createMapKey(albumId, albumEntryId, foundLength, true), createdUrl);
        return this.sanitizer.bypassSecurityTrustResourceUrl(createdUrl.url);
    }

    public async getVideoBlob(albumId: string, albumEntryId: string, minSize: number): Promise<ImageBlob> {
        const maxShift = navigator.onLine ? 3 : 10;
        return await this.fetchVideo(albumId, albumEntryId, minSize, maxShift);
    }

    private async fetchVideo(albumId: string, albumEntryId: string, minSize: number, maxShift: number): Promise<ImageBlob> {
        // const startTime = Date.now();
        const cache = await this.videoCache;
        const nextStepMaxLength = findNextStep(minSize);
        for (let shift = 0; shift < maxShift; shift++) {
            // tslint:disable-next-line:no-bitwise
            const length = nextStepMaxLength * (1 << shift);
            if (length > 3200) {
                continue;
            }
            const candidateSrc = videoUrl(albumId, albumEntryId, length);
            const cacheEntry = await cache.match(candidateSrc);
            if (cacheEntry) {
                // console.log('cache lookup: ', shift, Date.now() - startTime);
                return {
                    albumId,
                    albumEntryId,
                    mediaSize: nextStepMaxLength,
                    data: await cacheEntry.blob()
                };
            }
        }
        if (!navigator.onLine) {
            throw new Error('Offline');
        }
        // console.log('cache test: ', Date.now() - startTime);
        const src = videoUrl(albumId, albumEntryId, nextStepMaxLength);

        for (let i = 0; i < 10; i++) {
            try {
                const imageBlob = await this.http.get(src, {responseType: 'blob'}).toPromise();
                cache.put(src, new Response(imageBlob)).then();
                if (!imageBlob.type.startsWith('video')) {
                    console.error(`wrong content type of ${albumEntryId}: ${imageBlob.type}`);
                    continue;
                }

                return {
                    albumId,
                    albumEntryId,
                    mediaSize: nextStepMaxLength,
                    data: imageBlob
                };
            } catch (error) {
                console.error(`Cannot load ${albumEntryId}, try ${i}`, error);
            }
        }
        throw new Error('Error fetching image');
    }


    /*public ngOnDestroy(): void {
        this.stopRunningTimer();
    }*/
    public listOfflineAvailableVersions(): Promise<Map<string, string>> {
        return this.storageService.listOfflineAvailableVersions();
    }

    public async storeScreenSize(screenSize: number): Promise<void> {
        const deviceData = await this.storageService.getDeviceData();
        if (deviceData === undefined) {
            await this.storageService.setDeviceData({screenSize});
        } else if (deviceData.screenSize !== screenSize) {
            deviceData.screenSize = screenSize;
            await this.storageService.setDeviceData(deviceData);
        }
    }

    private async fetchAlbumIfMissing(albumId: string): Promise<void> {
        const [storedAlbum, storedSettings] = await this.storageService.getAlbumAndSettings(albumId);
        if (storedAlbum === undefined || storedSettings === undefined || storedAlbum.albumVersion !== storedSettings.albumEntryVersion) {
            await this.fetchAlbum(albumId);
        }
    }

    public async clearCachedData(): Promise<void> {
        await this.storageService.clearCaches();
        await caches.delete(IMAGE_CACHE_NAME);
        this.initCache();
    }

    public async getAlbum(albumId: string): Promise<AlbumData> {
        const storedAlbum = await this.storageService.getAlbum(albumId);
        if (storedAlbum) {
            return storedAlbum;
        }
        await this.fetchAlbumIfMissing(albumId);
        return this.storageService.getAlbum(albumId);
    }

    public appendDiashow(albumId: string, mediaId: string): Promise<void> {
        return this.storageService.appendDiashow({albumId, albumEntryId: mediaId})
            .then();

    }

    public fetchNextDiashowEntry(filter?: Predicate<DiashowEntry>): Promise<DiashowEntry> {
        return this.storageService.getRandomDiashowEntry(filter);
    }

    public async removeDiashow(albumId: string, mediaId: string): Promise<void> {
        return this.storageService.removeDiashow({albumId, albumEntryId: mediaId})
            .then();
    }

    public async isDiashowEnabled(albumId: string, mediaId: string): Promise<boolean> {
        return this.storageService.containsDiashow({albumId, albumEntryId: mediaId});
    }

    public async filterInDiashow(selectedEntries: Set<[string, string]>): Promise<Set<[string, string]>> {

        const filterEntries = new Set<DiashowEntry>();
        selectedEntries.forEach(([album, entry]) => {
            filterEntries.add({albumId: album, albumEntryId: entry});
        });
        const foundHits: DiashowEntry[] = await this.storageService.findDiashowHits(filterEntries);
        const ret = new Set<[string, string]>();
        foundHits.forEach(entry => {
            if (entry !== undefined) {
                ret.add([entry.albumId, entry.albumEntryId]);
            }
        });
        return ret;

    }


    private findLoadedBlob(albumId: string, albumEntryId: string, minSize: number, maxShift: number, video: boolean): ImageUrl | undefined {
        const nextStepMaxLength = findNextStep(minSize);
        for (let shift = 0; shift < maxShift; shift++) {
            // tslint:disable-next-line:no-bitwise
            const length = nextStepMaxLength * (1 << shift);
            const cachedBlob = this.loadedBlobs.get(
                createMapKey(albumId, albumEntryId, length, video));
            if (cachedBlob) {
                return cachedBlob;
            }
        }
        return undefined;
    }
}

function findNextStep(maxLength: number): number {
    if (maxLength > 1600) {
        return 3200;
    }
    if (maxLength > 800) {
        return 1600;
    }
    if (maxLength > 400) {
        return 800;
    }
    if (maxLength > 200) {
        return 400;
    }
    if (maxLength > 100) {
        return 200;
    }
    if (maxLength > 50) {
        return 100;
    }
    return 50;
}

