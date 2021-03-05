import {Component, NgZone, OnInit} from '@angular/core';

import {Platform} from '@ionic/angular';
import {SplashScreen} from '@ionic-native/splash-screen/ngx';
import {StatusBar} from '@ionic-native/status-bar/ngx';
import {CommonServerApiService, MenuEntry} from './service/common-server-api.service';


@Component({
    selector: 'app-root',
    templateUrl: 'app.component.html',
    styleUrls: ['app.component.css']
})
export class AppComponent implements OnInit {
    public selectedCollectionIndex = -1;
    public photoCollections: MenuEntry[] = [];
    public visibleCollections: MenuEntry[] = [];
    public labels = ['Family', 'Friends', 'Notes', 'Work', 'Travel', 'Reminders'];
    private photoCollectionFilter = '';

    constructor(
        private commonServerApiService: CommonServerApiService,
        private ngZone: NgZone,
        private platform: Platform,
        private splashScreen: SplashScreen,
        private statusBar: StatusBar
    ) {
        this.initializeApp();
    }


    initializeApp() {
        this.platform.ready().then(() => {
            this.statusBar.styleDefault();
            this.splashScreen.hide();
        });
    }

    ngOnInit() {
        this.updatePhotoCollectionList();
    }

    updatePhotoCollectionList() {
        this.commonServerApiService.listCollections(this.photoCollectionFilter).then((entries: MenuEntry[]) => {
            this.ngZone.run(() => {
                this.visibleCollections = entries;
                const path = window.location.pathname;
                if (path !== undefined) {
                    this.selectedCollectionIndex = this.visibleCollections.findIndex(page => path.startsWith(page.url));
                } else {
                    this.selectedCollectionIndex = -1;
                }
            });
        });
    }

    updateSearch(event: CustomEvent) {
        this.photoCollectionFilter = event.detail.value;
        this.updatePhotoCollectionList();
    }
}
