import {Injectable} from '@angular/core';
import {Album, Label} from '../generated/graphql';
import {DataService} from './data.service';
import {AlbumData} from './storage.service';


export type AlbumEntryDataType = { __typename?: 'Album' } &
    Pick<Album, 'id' | 'name' | 'entryCount' | 'albumTime'> &
    {
        labels: Array<{ __typename?: 'Label' } & Pick<Label, 'labelName' | 'labelValue'>>
    };
export type MenuEntry = { url: string, data: AlbumData };


@Injectable({
    providedIn: 'root'
})
export class CommonServerApiService {

    constructor(private dataService: DataService) {
    }

    public listCollections(filter?: string): Promise<MenuEntry[]> {
        return this.doListCollections().then(list => {
            if (filter === undefined && filter === '') {
                return list;
            } else {
                const filterValue = RegExp(filter, 'i');
                return list.filter(entry => entry.data.title.toLowerCase().search(filterValue) >= 0);
            }
        });
    }

    private async doListCollections(): Promise<MenuEntry[]> {
        const albumList = await this.dataService.listAlbums();
        return albumList.sort((a, b) => {
            const aValue = a.albumTime ? a.albumTime : 0;
            const bValue = b.albumTime ? b.albumTime : 0;
            return bValue - aValue;
        }).map((entry: AlbumData) => ({
            url: '/album/' + entry.id, data: entry
        }));
    }

    public clean() {
        // this.lastCollectionList = undefined;
    }
}
