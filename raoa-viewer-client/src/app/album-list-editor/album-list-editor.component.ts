import {Component, EventEmitter, Input, NgZone, OnInit, Output} from '@angular/core';
import {DataService} from '../service/data.service';
import {AlbumData} from '../service/storage.service';


@Component({
    selector: 'app-album-list-editor',
    templateUrl: './album-list-editor.component.html',
    styleUrls: ['./album-list-editor.component.scss'],
})
export class AlbumListEditorComponent implements OnInit {

    @Input()
    public selectedCollections: Set<string> = new Set();
    @Output()
    valueChanged = new EventEmitter<Set<string>>();
    private groupFilter = '';
    private filterValue = '';
    private sortedList: AlbumData[];
    public filteredList: AlbumData[];

    constructor(private dataService: DataService,
                private ngZone: NgZone) {
    }

    async ngOnInit() {
        const data = (await this.dataService.listAlbums()).filter(a => a[0].albumTime).sort((a1, a2) => a1[0].albumTime - a2[0].albumTime);
        this.ngZone.run(() => {
            this.sortedList = data.map(e => e[0]);
            this.filterList();
        });

    }

    public filterChanged($event: CustomEvent) {
        this.filterValue = $event.detail.value.toLowerCase();
        this.filterList();
    }

    private filterList() {
        this.filteredList = this.sortedList.filter(entry => entry.title.toLowerCase().indexOf(this.filterValue) >= 0);
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
