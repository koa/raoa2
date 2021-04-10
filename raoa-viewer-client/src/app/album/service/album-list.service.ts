import {Injectable} from '@angular/core';
import {Album, AlbumContentGQL, AlbumEntry} from '../../generated/graphql';
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
    keywords: Map<string, number>;
    labels: Map<string, string>;
}

@Injectable({
    providedIn: 'root'
})
export class AlbumListService {
    lastAlbumId: string = undefined;
    lastResult: AlbumData;

    constructor(private serverApi: ServerApiService, private albumContentGQL: AlbumContentGQL, private router: Router) {
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
            const sortedEntries: (QueryAlbumEntry)[] = content.albumById.entries
                .slice()
                .sort((e1, e2) => {
                    const c1 = e1?.created;
                    const c2 = e2?.created;
                    return c1 === c2 ? e1.name.localeCompare(e2.name) : c1 === null || c1 === undefined ? 1 : c1.localeCompare(c2);
                });
            const keywords = new Map<string, number>();
            const labels = new Map<string, string>();
            content.albumById.keywordCounts.forEach(e => keywords.set(e.keyword, e.count));
            if (content.albumById.labels) {
                content.albumById.labels.forEach(e => labels.set(e.labelName, e.labelValue));
            }
            const result = {title, sortedEntries, canManageUsers: content.currentUser?.canManageUsers, keywords, labels};
            this.lastAlbumId = albumId;
            this.lastResult = result;
            return result;
        });
    }

    public async clearAlbum(albumId: string) {
        if (this.lastAlbumId === albumId) {
            this.lastResult = undefined;
            this.lastAlbumId = undefined;
        }
        await this.serverApi.clear();
    }
}
