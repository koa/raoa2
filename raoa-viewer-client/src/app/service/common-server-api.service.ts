import {Injectable} from '@angular/core';
import {Album, Label} from '../generated/graphql';
import {DataService} from './data.service';
import {AlbumData} from './storage.service';


export type AlbumEntryDataType = { __typename?: 'Album' } &
    Pick<Album, 'id' | 'name' | 'entryCount' | 'albumTime'> &
    {
        labels: Array<{ __typename?: 'Label' } & Pick<Label, 'labelName' | 'labelValue'>>
    };


@Injectable({
    providedIn: 'root'
})
export class CommonServerApiService {

    constructor(private dataService: DataService) {
    }

    public async listCollections(online: boolean, filter?: string): Promise<AlbumData[]> {
        const list: AlbumData[] = await this.doListCollections();
        let filteredList: AlbumData[];
        if (filter === undefined || filter === '') {
            filteredList = list;
        } else {
            const filterValue = RegExp(filter, 'i');
            filteredList = list.filter(entry => entry.title.toLowerCase().search(filterValue) >= 0);
        }
        if (online) {
            return filteredList;
        } else {
            return filteredList.filter(entry => entry.albumVersion === entry.offlineSyncedVersion);
        }
    }

    private async doListCollections(): Promise<AlbumData[]> {
        const albumList: AlbumData[] = await this.dataService.listAlbums();
        return albumList.sort((a, b) => {
            const aValue = a.albumTime ? a.albumTime : 0;
            const bValue = b.albumTime ? b.albumTime : 0;
            return bValue - aValue;
        });
    }

    public clean() {
        // this.lastCollectionList = undefined;
    }
}
