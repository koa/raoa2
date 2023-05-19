import {Component, NgZone, OnDestroy, OnInit, Predicate} from '@angular/core';
import {MultiWindowService} from 'ngx-multi-window';
import {ShowMedia} from '../interfaces/show-media';
import {DataService} from '../service/data.service';
import {DiashowEntry} from '../service/storage.service';
import {SafeUrl} from '@angular/platform-browser';

@Component({
    selector: 'app-presentation',
    templateUrl: './presentation.page.html',
    styleUrls: ['./presentation.page.css'],
})
export class PresentationPage implements OnInit, OnDestroy {
    private bigImageSize = 800;
    public playVideo = false;
    public currentMediaContent: SafeUrl = undefined;
    private visibleEntry: DiashowEntry | undefined;
    private runningTimer: NodeJS.Timeout | undefined;

    constructor(private multiWindowService: MultiWindowService,
                private ngZone: NgZone,
                private dataService: DataService) {
    }

    async ngOnInit() {
        this.multiWindowService.onMessage().subscribe(value => {
            let event: string = value.event;
            if (event === 'showMedia') {
                const data: ShowMedia = value.data;
                let visibleEntry: DiashowEntry = {albumId: data.albumId, albumEntryId: data.mediaId};
                this.showEntry(visibleEntry);
                this.visibleEntry = visibleEntry;
                this.showVisibleEntry();
            }
        });
        await this.displayAnyEntry();
    }

    private async showVisibleEntry() {
        const image = await this.dataService.getImage(this.visibleEntry.albumId, this.visibleEntry.albumEntryId, this.bigImageSize);
        this.ngZone.run(() => {
            this.currentMediaContent = image;
        });
    }

    startTimer() {
        if (this.runningTimer !== undefined) {
            clearTimeout(this.runningTimer);
        }
        this.runningTimer = undefined;
        this.runningTimer = setTimeout(() => this.displayAnyEntry(), 5 * 1000);
    }

    private async displayAnyEntry(): Promise<void> {
        let filter: Predicate<DiashowEntry>;
        const excludeEntry = this.visibleEntry;
        if (excludeEntry !== undefined) {
            filter = candidate => {
                return candidate.albumId !== excludeEntry.albumId
                    || candidate.albumEntryId !== excludeEntry.albumEntryId;
            };
        }
        const foundEntry = await this.dataService.fetchNextDiashowEntry(filter);
        if (foundEntry) {
            this.showEntry(foundEntry);
        }
    }

    public ngOnDestroy() {
        if (this.runningTimer !== undefined) {
            clearTimeout(this.runningTimer);
        }
        this.runningTimer = undefined;
    }

    public async resized() {
        this.bigImageSize = Math.max(window.screen.width, window.screen.height);
    }

    private async showEntry(foundEntry: DiashowEntry) {
        this.visibleEntry = foundEntry;
        const image = await this.dataService.getImage(this.visibleEntry.albumId, this.visibleEntry.albumEntryId, this.bigImageSize);
        this.ngZone.run(() => {
            this.currentMediaContent = image;
        });
        this.startTimer();
    }
}
