import {Injectable} from '@angular/core';
import {ServerApiService} from './server-api.service';
import {Album, AllAlbumsGQL} from '../generated/graphql';


export type AlbumEntryDataType = { __typename?: 'Album' } & Pick<Album, 'id' | 'name' | 'entryCount' | 'albumTime'>;
export type MenuEntry = { url: string, data: AlbumEntryDataType };


@Injectable({
    providedIn: 'root'
})
export class CommonServerApiService {
    private lastCollectionList: MenuEntry[];

    constructor(private serverApi: ServerApiService,
                private albumListGQL: AllAlbumsGQL,
    ) {
    }

    public listCollections(filter?: string): Promise<MenuEntry[]> {
        return this.doListCollections().then(list => {
            if (filter === undefined && filter === '') {
                return list;
            } else {
                const filterValue = RegExp(filter, 'i');
                return list.filter(entry => entry.data.name.toLowerCase().search(filterValue) >= 0);
            }
        });
    }

    private doListCollections(): Promise<MenuEntry[]> {
        if (this.lastCollectionList !== undefined) {
            return Promise.resolve(this.lastCollectionList);
        }
        return this.serverApi.query(this.albumListGQL, {}).then(result => {
            return result.listAlbums.filter(a => a.albumTime != null)
                .sort((a, b) => -a.albumTime.localeCompare(b.albumTime))
                .map((entry: AlbumEntryDataType) => ({
                    url: '/album/' + entry.id, data: entry
                }))
                ;
        }).then(result => {
            this.lastCollectionList = result;
            return result;
        });
    }

    public clean() {
        this.lastCollectionList = undefined;
    }
}
