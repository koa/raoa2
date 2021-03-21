import {Component, EventEmitter, Input, NgZone, OnInit, Output} from '@angular/core';
import {ServerApiService} from '../service/server-api.service';
import {Album, AllAlbumsGQL} from '../generated/graphql';

type AlbumType = { __typename?: 'Album' } & Pick<Album, 'id' | 'name' | 'entryCount' | 'albumTime'>;

@Component({
    selector: 'app-album-list-editor',
    templateUrl: './album-list-editor.component.html',
    styleUrls: ['./album-list-editor.component.scss'],
})
export class AlbumListEditorComponent implements OnInit {

    private groupFilter = '';
    @Input('selectedCollections')
    public selectedCollections: Set<string> = new Set();
    @Output()
    valueChanged = new EventEmitter<Set<string>>();
    private filterValue = '';
    private sortedList: AlbumType[];
    public filteredList: AlbumType[];

    constructor(private serverApi: ServerApiService,
                private albumListGQL: AllAlbumsGQL,
                private ngZone: NgZone) {
    }

    async ngOnInit() {
        const data = await this.serverApi.query(this.albumListGQL, {});
        this.ngZone.run(() => {
            const sortedList: (AlbumType)[] = data.listAlbums.filter(a => a.albumTime).sort((a1, a2) => -a1.albumTime.localeCompare(a2.albumTime));
            this.sortedList = sortedList;
            this.filterList();
        });

    }

    public filterChanged($event: CustomEvent) {
        this.filterValue = $event.detail.value.toLowerCase();
        this.filterList();
    }

    private filterList() {
        this.filteredList = this.sortedList.filter(entry => entry.name.toLowerCase().indexOf(this.filterValue) >= 0);
    }

    toggle(id: string) {
        if (this.selectedCollections.has(id)) {
            this.selectedCollections.delete(id);
        } else {
            this.selectedCollections.add(id);
        }
        this.valueChanged.emit(this.selectedCollections);
    }
}
