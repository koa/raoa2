import {Injectable} from '@angular/core';
import {Album, AlbumContentGQL, AlbumEntry} from '../../generated/graphql';
import {ServerApiService} from '../../service/server-api.service';
import {Maybe} from 'graphql/jsutils/Maybe';

export type QueryAlbumEntry =
    { __typename?: 'AlbumEntry' }
    & Pick<AlbumEntry, 'id' | 'name' | 'entryUri' | 'targetWidth' | 'targetHeight' | 'created' | 'keywords'>;

type AlbumListResult = Maybe<{ __typename?: 'Query' } &
    { albumById?: Maybe<{ __typename?: 'Album' } & Pick<Album, 'name'> & { entries: Array<QueryAlbumEntry> }> }>;

export interface AlbumData {
    title: string | null;
    sortedEntries: (QueryAlbumEntry)[];
}

@Injectable({
    providedIn: 'root'
})
export class AlbumListService {
    lastAlbumId: string = undefined;
    lastResult: AlbumData;

    constructor(private serverApi: ServerApiService, private albumContentGQL: AlbumContentGQL) {
    }

    public listAlbum(albumId: string): Promise<AlbumData> {
        if (this.lastAlbumId === albumId) {
            return Promise.resolve(this.lastResult);
        }
        return this.serverApi.query(this.albumContentGQL, {albumId}).then(content => {
            const title: string | null = content.albumById.name;
            const sortedEntries: (QueryAlbumEntry)[] = content.albumById.entries
                .slice()
                .sort((e1, e2) => {
                    const c1 = e1?.created;
                    const c2 = e2?.created;
                    return c1 === c2 ? e1.name.localeCompare(e2.name) : c1 === null || c1 === undefined ? 1 : c1.localeCompare(c2);
                });
            const result = {title, sortedEntries};
            this.lastAlbumId = albumId;
            this.lastResult = result;
            return result;
        });
    }
}
