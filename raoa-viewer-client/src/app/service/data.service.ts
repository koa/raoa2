import {Injectable} from '@angular/core';
import {
    Album,
    AlbumContentGQL,
    AlbumEntry,
    AllAlbumsGQL,
    MutationData,
    SingleAlbumMutateGQL,
    UserPermissionsGQL
} from '../generated/graphql';
import {ServerApiService} from './server-api.service';
import {Maybe} from 'graphql/jsutils/Maybe';
import {Router} from '@angular/router';
import {AlbumData, AlbumEntryData, KeywordState, StorageService} from '../service/storage.service';
import {FNCH_COMPETITION_ID} from '../constants';

export type QueryAlbumEntry =
    { __typename?: 'AlbumEntry' }
    & Pick<AlbumEntry, 'id' | 'name' | 'entryUri' | 'targetWidth' | 'targetHeight' | 'created' | 'keywords'>;

type AlbumListResult = Maybe<{ __typename?: 'Query' } &
    { albumById?: Maybe<{ __typename?: 'Album' } & Pick<Album, 'name'> & { entries: Array<QueryAlbumEntry> }> }>;

export interface LocalAlbumData {
    title: string | null;
    sortedEntries: (QueryAlbumEntry)[];
    keywords: Map<string, number>;
    labels: Map<string, string>;
}

export interface UserPermissions {
    canManageUsers: boolean;
    canEdit: boolean;
}


function sortAlbumEntries(albumEntries: IterableIterator<QueryAlbumEntry> | QueryAlbumEntry[]): QueryAlbumEntry[] {
    const ret: QueryAlbumEntry [] = [];
    for (const entry of albumEntries) {
        if (entry.created) {
            ret.push(entry);
        }
    }
    ret.sort((e1, e2) => {
        const c1 = e1?.created;
        const c2 = e2?.created;
        return c1 === c2 ? e1.name.localeCompare(e2.name) : c1 === null || c1 === undefined ? 1 : c1.localeCompare(c2);
    });
    return ret;
}

function createKeywordStats(albumEntries: QueryAlbumEntry[]) {
    const keywords = new Map<string, number>();
    albumEntries.forEach(entry => {
        const kw = entry.keywords;
        if (kw) {
            kw.forEach(keyword => {
                if (keywords.has(keyword)) {
                    keywords.set(keyword, keywords.get(keyword) + 1);
                } else {
                    keywords.set(keyword, 1);
                }
            });
        }
    });
    return keywords;
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
export class DataService {


    constructor(private serverApi: ServerApiService,
                private albumContentGQL: AlbumContentGQL,
                private allAlbumsGQL: AllAlbumsGQL,
                private userPermissionsGQL: UserPermissionsGQL,
                private router: Router,
                private singleAlbumMutateGQL: SingleAlbumMutateGQL,
                private storageService: StorageService) {
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
                    lastUpdated: Date.now()
                });
            });
            return this.storageService.updateAlbums(data);
        });
    }

    public async listAlbums(): Promise<AlbumData[]> {
        return await this.storageService.listAlbums();
    }

    public async listAlbum(albumId: string): Promise<[AlbumData, AlbumEntryData[]]> {
        const [album, entries] = await this.storageService.listAlbum(albumId);
        if (album === undefined || entries === undefined) {
            const content = await this.serverApi.query(this.albumContentGQL, {albumId});
            const albumById = content.albumById;
            if (!albumById) {
                await this.router.navigate(['/']);
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
                lastUpdated: Date.now()

            };
            await this.storageService.updateAlbumEntries(newEntries, newAlbum);
            return await this.listAlbum(albumId);
        }
        return [album, entries];
    }

    public async modifyAlbum(updates: MutationData[]): Promise<void> {
        for (let i = 0; i < updates.length; i += 100) {
            const result = await this.serverApi.update(this.singleAlbumMutateGQL,
                {updates: updates.slice(i, Math.min(i + 100, updates.length))});
            const mutate = result.mutate;
            if (mutate) {
                if (mutate.errors && mutate.errors.length > 0) {
                    const messages = mutate.errors.map(m => m.message).join(', ');
                    /*const toastElement = await this.toastController.create({
                        message: 'Fehler beim Speichern' + messages + '"',
                        duration: 10000,
                        color: 'danger'
                    });
                    await toastElement.present();*/
                }
                if (mutate.modifiedEntries && mutate.modifiedEntries.length > 0) {
                    const modifiedEntries: AlbumEntryData[] = [];
                    mutate.modifiedEntries.forEach(entry => modifiedEntries.push(createStoreEntry(entry, entry.album.id)));
                    await this.storageService.updateAlbumEntries(modifiedEntries);
                }
            }
        }
    }

    public async clearAlbum(albumId: string) {
        await this.serverApi.clear();
    }

    public async userPermission(): Promise<UserPermissions> {
        const data = await this.serverApi.query(this.userPermissionsGQL, {});
        return {
            canEdit: data.currentUser.canEdit,
            canManageUsers: data.currentUser.canManageUsers
        };
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

    async hasPendingMutations(albumId: string): Promise<boolean> {
        return this.storageService.hasPendingMutations(albumId);
    }

    async clearPendingMutations(albumId: string) {
        return this.storageService.clearPendingMutations(albumId);
    }
}
