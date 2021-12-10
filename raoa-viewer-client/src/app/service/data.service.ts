import {Injectable} from '@angular/core';
import {
    Album,
    AlbumContentGQL,
    AlbumEntry,
    AllAlbumVersionsGQL,
    GetAlbumDetailsGQL,
    Label,
    MutationData,
    SingleAlbumMutateGQL,
    UserPermissionsGQL
} from '../generated/graphql';
import {ServerApiService} from './server-api.service';
import {Router} from '@angular/router';
import {AlbumData, AlbumEntryData, AlbumSettings, ImageBlob, KeywordState, StorageService} from './storage.service';
import {FNCH_COMPETITION_ID} from '../constants';
import {HttpClient} from '@angular/common/http';
import {LoginService} from './login.service';
import {Observable, Subscriber} from 'rxjs';
import {share} from 'rxjs/operators';

export type QueryAlbumEntry =
    { __typename?: 'AlbumEntry' }
    & Pick<AlbumEntry, 'id' | 'name' | 'entryUri' | 'targetWidth' | 'targetHeight' | 'created' | 'keywords'>;

export interface UserPermissions {
    canManageUsers: boolean;
    canEdit: boolean;
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
        entryType: albumEntry.contentType.startsWith('video') ? 'video' : 'image',
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


function createStoreAlbum(album: { __typename?: 'Album' } & Pick<Album, 'id' | 'name' | 'entryCount' | 'albumTime' | 'version'> &
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

export function createFilter(filteringKeywords: string[], keywordCombine: 'and' | 'or', filteringTimeRange: [number, number]):
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

@Injectable({
    providedIn: 'root'
})
export class DataService {// implements OnDestroy {
    private runningTimer: number = undefined;
    private syncRunning = false;
    private syncEnabled = true;
    private syncStateId = 0;
    private albumMutations: Observable<string>;
    private mutationSubscribe: Subscriber<string>;

    constructor(private serverApi: ServerApiService,
                private albumContentGQL: AlbumContentGQL,
                private allAlbumVersionsGQL: AllAlbumVersionsGQL,
                private getAlbumDetailsGQL: GetAlbumDetailsGQL,
                private userPermissionsGQL: UserPermissionsGQL,
                //private subscribeAlbumMutationsGQL: SubscribeAlbumMutationsGQL,
                private router: Router,
                private singleAlbumMutateGQL: SingleAlbumMutateGQL,
                private storageService: StorageService,
                private http: HttpClient, loginService: LoginService) {
        this.albumMutations = new Observable<string>(subscribe => {
            this.mutationSubscribe = subscribe;
        }).pipe(share());
        if (navigator.onLine && loginService.hasValidToken()) {
            this.updateAlbumData().then();
        } else {
        }
        this.setTimer();
    }

    async updateAlbumData() {

        /*this.serverApi
            .subscribe(this.subscribeAlbumMutationsGQL, {})
            .subscribe(album => console.log(album));
*/
        const [albumVersionList, storedAlbumEnties] = await
            Promise.all([this.serverApi.query(this.allAlbumVersionsGQL, {}),
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
                    fetchedAlbums.forEach(album => this.mutationSubscribe.next(album.id));
                });
                albumDataBatch = [];
            }
            albumDataBatch.push(this.serverApi.query(this.getAlbumDetailsGQL, {albumId}).then(v => createStoreAlbum(v.albumById)));
        }
        await Promise.all(albumDataBatch).then(async fetchedAlbums => {
            await this.storageService.updateAlbums(fetchedAlbums);
            fetchedAlbums.forEach(album => this.mutationSubscribe.next(album.id));
        });
        const removedAlbums = await this.storageService.keepAlbums(keepAlbums);
        if (removedAlbums.length > 0) {
            removedAlbums.forEach(albumId => this.mutationSubscribe.next(albumId));
        }
    }


    private setTimer() {
        this.stopRunningTimer();
        this.runningTimer = setInterval(() => {
            this.doSync().then();
        }, 10 * 1000);
        const connection: NetworkInformation = navigator.connection;
        const syncEnabledBefore = this.syncEnabled;
        if (connection) {
            const type = connection.type;
            this.syncEnabled = type === undefined || type === 'wifi' || type === 'ethernet';
            connection.addEventListener('change', () => {
                const c = navigator.connection;
                this.syncEnabled = c.type === undefined || c.type === 'wifi' || c.type === 'ethernet';
            });
        } else {
            this.syncEnabled = true;
        }
        if (!syncEnabledBefore && this.syncEnabled) {
            this.updateAlbumData().then();
        }
    }

    public albumModified(): Observable<string> {
        return this.albumMutations;
    }


    private stopRunningTimer() {
        if (this.runningTimer !== undefined) {
            clearInterval(this.runningTimer);
            this.runningTimer = undefined;
        }
    }

    private isSyncEnabled(): boolean {
        return navigator.onLine && this.syncEnabled;
    }

    public async doSync(): Promise<void> {
        if (!this.isSyncEnabled()) {
            return;
        }
        if (this.syncRunning) {
            return;
        }
        this.syncRunning = true;
        const startSyncState = this.syncStateId;
        try {
            const deviceData = await this.storageService.getDeviceData();
            const screenSize = deviceData?.screenSize || 3200;
            const albumsToUpdate = await this.storageService.findDeprecatedAlbums();
            for (const album of albumsToUpdate) {
                if (this.isSyncEnabled() && startSyncState === this.syncStateId) {
                    await this.fetchAlbum(album);
                }
            }
            if (!this.isSyncEnabled() || startSyncState !== this.syncStateId) {
                return;
            }
            const albumsToSync = await this.storageService.findAlbumsToSync();
            for (const album of albumsToSync) {
                const minSize = album.syncOffline;
                const missingEntries = await this.storageService.findMissingImagesOfAlbum(album.id);
                let batch: Promise<ImageBlob>[] = [];
                let pendingStore: Promise<void> = Promise.resolve();
                for (const entry of missingEntries[0]) {
                    if (!this.isSyncEnabled() || startSyncState !== this.syncStateId) {
                        return;
                    }
                    if (batch.length > 10) {
                        await pendingStore;
                        pendingStore = this.storageService.storeImages(await Promise.all(batch));
                        batch = [];
                    }
                    batch.push(this.fetchImage(album.id, entry, screenSize / 4));
                }
                for (const entry of missingEntries[1]) {
                    if (!this.isSyncEnabled() || startSyncState !== this.syncStateId) {
                        return;
                    }
                    if (batch.length > 10) {
                        await pendingStore;
                        pendingStore = this.storageService.storeImages(await Promise.all(batch));
                        batch = [];
                    }
                    batch.push(this.fetchImage(album.id, entry, screenSize));
                }
                await this.storageService.storeImages(await Promise.all(batch));
                await this.storageService.setOfflineSynced(album.id, album.albumEntryVersion);
            }
        } catch (error) {
            console.error(error);
        } finally {
            this.syncRunning = false;
        }
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

    private async fetchAlbum(albumId: string): Promise<void> {
        const oldAlbumState = await this.storageService.getAlbum(albumId);
        const content = await this.serverApi.query(this.albumContentGQL, {albumId});
        const albumById = content.albumById;
        if (!albumById) {
            // await this.router.navigate(['/']);
            throw new Error('insufficient permissions');
        }

        const newEntries: AlbumEntryData[] = [];
        albumById.entries.forEach(albumEntry => {
            newEntries.push(createStoreEntry(albumEntry, albumId));
        });
        await this.storageService.updateAlbums([createStoreAlbum(albumById)]);
        await this.storageService.updateAlbumEntries(newEntries, albumId, albumById.version);
    }

    public async modifyAlbum(updates: MutationData[]): Promise<void> {
        const MUTATION_BATCH_SIZE = 50;
        for (let i = 0; i < updates.length; i += MUTATION_BATCH_SIZE) {
            const result = await this.serverApi.update(this.singleAlbumMutateGQL,
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
            return storedPermissions;
        }
        if (!navigator.onLine) {
            throw new Error('Offline');
        }
        const data = await this.serverApi.query(this.userPermissionsGQL, {});
        const ret = {
            canEdit: data.currentUser.canEdit,
            canManageUsers: data.currentUser.canManageUsers
        };
        await this.storageService.setUserPermissions(ret);
        return ret;
    }

    public async addKeyword(albumId: string, selectedEntries: string[], keyword: string[]) {
        await this.storageService.addKeyword(albumId, selectedEntries, keyword);
        this.mutationSubscribe.next(albumId);
    }

    public async removeKeyword(albumId: string, selectedEntries: string[], keyword: string[]) {
        await this.storageService.removeKeyword(albumId, selectedEntries, keyword);
        this.mutationSubscribe.next(albumId);
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
            touchedAlbums.forEach(id => this.mutationSubscribe.next(id));
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

    public async getImage(albumId: string, albumEntryId: string, minSize: number): Promise<string> {
        const storedImage = await this.storageService.readImage(albumId, albumEntryId, minSize, navigator.onLine);
        if (storedImage !== undefined) {
            return encodeDataUrl(storedImage);
        }
        if (!navigator.onLine) {
            throw new Error('Offline');
        }
        const fetchedImage = await this.fetchImage(albumId, albumEntryId, minSize);
        await this.storageService.storeImage(fetchedImage);
        return encodeDataUrl(fetchedImage);
    }

    private async fetchImage(albumId: string, albumEntryId: string, minSize: number): Promise<ImageBlob> {
        const nextStepMaxLength = findNextStep(minSize);
        const src = '/rest/album/' + albumId + '/' + albumEntryId + '/thumbnail?maxLength=' + nextStepMaxLength;
        for (let i = 0; i < 10; i++) {
            try {
                const imageBlob = await this.http.get(src, {responseType: 'blob'}).toPromise();
                if (!imageBlob.type.startsWith('image')) {
                    console.error(`wrong content type of ${albumEntryId}: ${imageBlob.type}`);
                    continue;
                }
                const data: ImageBlob = {
                    albumId,
                    albumEntryId,
                    mediaSize: nextStepMaxLength,
                    data: imageBlob
                };
                return data;
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

function encodeDataUrl(storedImage: ImageBlob): Promise<string> {
    const imageBlob = storedImage.data;
    const reader = new FileReader();
    return new Promise((resolve, reject) => {
        reader.onloadend = () => resolve(reader.result as string);
        reader.readAsDataURL(imageBlob);
    });
}
