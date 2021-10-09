import {Injectable} from '@angular/core';
import {Album, AlbumContentGQL, AlbumEntry, MutationData, SingleAlbumMutateGQL} from '../../generated/graphql';
import {ServerApiService} from '../../service/server-api.service';
import {Maybe} from 'graphql/jsutils/Maybe';
import {Router} from '@angular/router';

export type QueryAlbumEntry =
    { __typename?: 'AlbumEntry' }
    & Pick<AlbumEntry, 'id' | 'name' | 'entryUri' | 'targetWidth' | 'targetHeight' | 'created' | 'keywords'>;

type AlbumListResult = Maybe<{ __typename?: 'Query' } &
    { albumById?: Maybe<{ __typename?: 'Album' } & Pick<Album, 'name'> & { entries: Array<QueryAlbumEntry> }> }>;

export interface AlbumData {
    title: string | null;
    sortedEntries: (QueryAlbumEntry)[];
    canManageUsers: boolean;
    canEdit: boolean;
    keywords: Map<string, number>;
    labels: Map<string, string>;
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

@Injectable({
    providedIn: 'root'
})
export class AlbumListService {
    lastAlbumId: string = undefined;
    lastResult: AlbumData;

    constructor(private serverApi: ServerApiService,
                private albumContentGQL: AlbumContentGQL,
                private router: Router,
                private singleAlbumMutateGQL: SingleAlbumMutateGQL) {
    }

    public listAlbum(albumId: string): Promise<AlbumData> {
        if (this.lastAlbumId === albumId) {
            return Promise.resolve(this.lastResult);
        }
        return this.serverApi.query(this.albumContentGQL, {albumId}).then(content => {
            if (!content.albumById) {
                this.router.navigate(['/']);
                throw new Error('insufficient permissions');
            }
            const title: string | null = content.albumById.name;
            const albumEntries: QueryAlbumEntry[] = content.albumById.entries;
            const sortedEntries = sortAlbumEntries(albumEntries);
            const keywords = createKeywordStats(albumEntries);
            const labels = new Map<string, string>();
            if (content.albumById.labels) {
                content.albumById.labels.forEach(e => labels.set(e.labelName, e.labelValue));
            }
            const result = {
                title,
                sortedEntries,
                canManageUsers: content.currentUser?.canManageUsers,
                canEdit: content.currentUser?.canEdit,
                keywords,
                labels
            };
            this.lastAlbumId = albumId;
            this.lastResult = result;
            return result;
        });
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
                if (this.lastResult) {
                    if (mutate.modifiedEntries && mutate.modifiedEntries.length > 0) {
                        const entryMap = new Map<string, QueryAlbumEntry>();
                        this.lastResult.sortedEntries.forEach(entry => entryMap.set(entry.id, entry));
                        mutate.modifiedEntries.forEach(modifiedEntry => {
                            if (modifiedEntry.album.id === this.lastAlbumId) {
                                entryMap.set(modifiedEntry.id, modifiedEntry);
                            }
                        });
                        this.lastResult.sortedEntries = sortAlbumEntries(entryMap.values());
                    }
                }
            }
        }
    }

    public async clearAlbum(albumId: string) {
        if (this.lastAlbumId === albumId) {
            this.lastResult = undefined;
            this.lastAlbumId = undefined;
        }
        await this.serverApi.clear();
    }
}
