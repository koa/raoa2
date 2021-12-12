import {Injectable} from '@angular/core';
import {Album, Label} from '../generated/graphql';
import {DataService} from './data.service';
import {AlbumData, AlbumSettings} from './storage.service';


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

    public async listCollections(online: boolean, filter?: string): Promise<[AlbumData, AlbumSettings | undefined][]> {
        const list: [AlbumData, (AlbumSettings | undefined)][] = await this.doListCollections();
        let filteredList: [AlbumData, (AlbumSettings | undefined)][];
        if (filter === undefined || filter === '') {
            filteredList = list;
        } else {
            const filterValue = RegExp(filter, 'i');
            filteredList = list.filter(entry => entry[0].title.toLowerCase().search(filterValue) >= 0);
        }
        if (online) {
            return filteredList;
        } else {
            return filteredList.filter(entry => entry[0].albumVersion === entry[1]?.offlineSyncedVersion);
        }
    }

    private async doListCollections(): Promise<[AlbumData, AlbumSettings | undefined][]> {
        const albumList: [AlbumData, AlbumSettings | undefined][] = await this.dataService.listAlbums();
        return albumList.sort((a, b) => {
            const aValue = a[0].albumTime ? a[0].albumTime : 0;
            const bValue = b[0].albumTime ? b[0].albumTime : 0;
            return bValue - aValue;
        });
    }

    public clean() {
        // this.lastCollectionList = undefined;
    }
}
