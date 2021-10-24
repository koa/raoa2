import {Injectable} from '@angular/core';
import {AlbumContentGQL, AlbumEntry, AllAlbumsGQL, MutationData, SingleAlbumMutateGQL, UserPermissionsGQL} from '../generated/graphql';
import {ServerApiService} from './server-api.service';
import {Router} from '@angular/router';
import {AlbumData, AlbumEntryData, ImageBlob, KeywordState, MAX_SMALL_IMAGE_SIZE, StorageService} from '../service/storage.service';
import {FNCH_COMPETITION_ID} from '../constants';
import {HttpClient} from '@angular/common/http';

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


@Injectable({
    providedIn: 'root'
})
export class DataService {// implements OnDestroy {
    private runningTimer: number | undefined = undefined;
    private syncRunning = false;

    constructor(private serverApi: ServerApiService,
                private albumContentGQL: AlbumContentGQL,
                private allAlbumsGQL: AllAlbumsGQL,
                private userPermissionsGQL: UserPermissionsGQL,
                private router: Router,
                private singleAlbumMutateGQL: SingleAlbumMutateGQL,
                private storageService: StorageService,
                private http: HttpClient) {
        if (navigator.onLine) {
            this.serverApi.query(this.allAlbumsGQL, {}).then(result => {
                const data: AlbumData[] = [];
                result.listAlbums.forEach(album => {
                    data.push({
                        albumTime: Date.parse(album.albumTime),
                        entryCount: album.entryCount,
                        albumEntryVersion: undefined,
                        albumVersion: album.version,
                        title: album.name,
                        fnchAlbumId: album.labels.filter(e => e.labelName === FNCH_COMPETITION_ID).map(e => e.labelValue).shift(),
                        id: album.id,
                        lastUpdated: Date.now(),
                        syncOffline: 0,
                        offlineSyncedVersion: undefined
                    });
                });
                return this.storageService.updateAlbums(data);
            });
        }
        this.setTimer();
    }


    private setTimer() {
        this.stopRunningTimer();
        this.runningTimer = setInterval(() => {
            this.doSync().then();
        }, 10 * 1000);
    }


    private stopRunningTimer() {
        if (this.runningTimer !== undefined) {
            clearInterval(this.runningTimer);
            this.runningTimer = undefined;
        }
    }

    public async doSync(): Promise<void> {
        if (!navigator.onLine) {
            return;
        }
        if (this.syncRunning) {
            return;
        }
        this.syncRunning = true;
        try {
            const albumsToUpdate = await this.storageService.findDeprecatedAlbums();
            for (const album of albumsToUpdate) {
                if (navigator.onLine) {
                    await this.fetchAlbum(album);
                }
            }
            if (!navigator.onLine) {
                return;
            }
            const albumsToSync = await this.storageService.findAlbumsToSync();
            for (const album of albumsToSync) {
                const minSize = album.syncOffline;
                const missingEntries = await this.storageService.findMissingImagesOfAlbum(album.id, minSize);
                let batch: Promise<ImageBlob>[] = [];
                let pendingStore: Promise<void> = Promise.resolve();
                for (const entry of missingEntries[0]) {
                    if (!navigator.onLine) {
                        return;
                    }
                    if (batch.length > 10) {
                        await pendingStore;
                        pendingStore = this.storageService.storeImages(await Promise.all(batch));
                        batch = [];
                    }
                    batch.push(this.fetchImage(album.id, entry, MAX_SMALL_IMAGE_SIZE));
                }
                for (const entry of missingEntries[1]) {
                    if (!navigator.onLine) {
                        return;
                    }
                    if (batch.length > 10) {
                        await pendingStore;
                        pendingStore = this.storageService.storeImages(await Promise.all(batch));
                        batch = [];
                    }
                    batch.push(this.fetchImage(album.id, entry, minSize));
                }
                await this.storageService.storeImages(await Promise.all(batch));
                await this.storageService.setOfflineSynced(album.id, album.albumEntryVersion);
            }
        } finally {
            this.syncRunning = false;
        }
    }

    public setSync(albumId: string, minSize: number) {
        this.storageService.setSync(albumId, minSize);
    }

    public async listAlbums(): Promise<AlbumData[]> {
        return await this.storageService.listAlbums();
    }

    public async listAlbum(albumId: string): Promise<[AlbumData, AlbumEntryData[]]> {
        const [album, entries] = await this.storageService.listAlbum(albumId);
        if (album === undefined || entries === undefined) {
            if (!navigator.onLine) {
                throw new Error('Offline');
            }
            try {
                await this.fetchAlbum(albumId);
            } catch (error) {
                await this.router.navigate(['/']);
            }
            return await this.listAlbum(albumId);
        }
        return [album, entries];
    }

    private async fetchAlbum(albumId: string) {
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
        const newAlbum: AlbumData = {
            albumTime: Date.parse(albumById.albumTime),
            entryCount: albumById.entryCount,
            albumEntryVersion: albumById.version,
            albumVersion: albumById.version,
            title: albumById.name,
            fnchAlbumId: albumById.labels.filter(e => e.labelName === FNCH_COMPETITION_ID).map(e => e.labelValue).shift(),
            id: albumById.id,
            lastUpdated: Date.now(),
            syncOffline: 0,
            offlineSyncedVersion: undefined
        };
        await this.storageService.updateAlbumEntries(newEntries, newAlbum);
    }

    public async modifyAlbum(updates: MutationData[]): Promise<void> {
        for (let i = 0; i < updates.length; i += 100) {
            const result = await this.serverApi.update(this.singleAlbumMutateGQL,
                {updates: updates.slice(i, Math.min(i + 100, updates.length))});
            const mutate = result.mutate;
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

    }

    public async removeKeyword(albumId: string, selectedEntries: string[], keyword: string[]) {
        await this.storageService.removeKeyword(albumId, selectedEntries, keyword);

    }

    public async currentKeywordStates(albumId: string, entries: Set<string>): Promise<Map<string, KeywordState>> {
        return await this.storageService.currentKeywordStates(albumId, entries);
    }

    async storeMutations(albumId: string) {
        if (!navigator.onLine) {
            return;
        }
        const mutations: MutationData[] = [];
        await Promise.all([
            this.storageService.pendingAddKeywords(albumId, pendingAddEntry => {
                mutations.push({
                    addKeywordMutation: {
                        albumId: pendingAddEntry.albumId,
                        keyword: pendingAddEntry.keyword,
                        albumEntryId: pendingAddEntry.albumEntryId
                    }
                });
            }),
            this.storageService.pendingRemoveKeywords(albumId, pendingRemoveEntry => {
                mutations.push({
                    removeKeywordMutation: {
                        albumId: pendingRemoveEntry.albumId,
                        keyword: pendingRemoveEntry.keyword,
                        albumEntryId: pendingRemoveEntry.albumEntryId
                    }
                });
            })
        ]);
        if (mutations.length > 0) {
            await this.modifyAlbum(mutations);
        }
    }

    public async getAlbumEntry(albumId: string, albumEntryId: string): Promise<[AlbumEntryData, Set<string>] | undefined> {
        const albumEntry = await this.storageService.getAlbumEntry(albumId, albumEntryId);
        if (albumEntry !== undefined) {
            return albumEntry;
        }
        await this.listAlbum(albumId);
        return this.storageService.getAlbumEntry(albumId, albumEntryId);
    }

    public async hasPendingMutations(albumId: string): Promise<boolean> {
        return this.storageService.hasPendingMutations(albumId);
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
