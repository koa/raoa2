import {Component, NgZone, OnInit} from '@angular/core';
import {MultiWindowService} from 'ngx-multi-window';
import {ShowMedia} from '../interfaces/show-media';
import {DataService} from '../service/data.service';

@Component({
    selector: 'app-presentation',
    templateUrl: './presentation.page.html',
    styleUrls: ['./presentation.page.css'],
})
export class PresentationPage implements OnInit {
    private bigImageSize = 800;
    public playVideo = false;
    public currentMediaContent: Promise<string> = undefined;

    constructor(private multiWindowService: MultiWindowService,
                private ngZone: NgZone,
                private dataService: DataService,) {
    }

    ngOnInit() {
        this.multiWindowService.onMessage().subscribe(value => {
            let event: string = value.event;
            if (event == 'showMedia') {
                const data: ShowMedia = value.data;
                this.ngZone.run(() => {
                    this.currentMediaContent = this.dataService.getImage(data.albumId, data.mediaId, this.bigImageSize);
                });
                console.log('Received a message from ', data.albumId, data.mediaId);
            }

        });
    }

    public async resized() {
        this.bigImageSize = Math.max(window.screen.width, window.screen.height);
    }

}
