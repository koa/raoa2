import {Component, NgZone, OnInit} from '@angular/core';

import {Platform} from '@ionic/angular';
import {SplashScreen} from '@ionic-native/splash-screen/ngx';
import {StatusBar} from '@ionic-native/status-bar/ngx';
import {ServerApiService} from './service/server-api.service';
import {AllAlbumsGQL} from './generated/graphql';

type MenuEntry = { title: string | null; url: string, albumId: string };

@Component({
    selector: 'app-root',
    templateUrl: 'app.component.html',
    styleUrls: ['app.component.scss']
})
export class AppComponent implements OnInit {
    public selectedIndex = 0;
    public appPages: MenuEntry[] = [];
    public labels = ['Family', 'Friends', 'Notes', 'Work', 'Travel', 'Reminders'];

    constructor(
        private serverApi: ServerApiService,
        private albumListGQL: AllAlbumsGQL,
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
        this.serverApi.query(this.albumListGQL, {}).then(result => {
            return result.listAlbums.filter(a => a.albumTime != null)
                .sort((a, b) => -a.albumTime.localeCompare(b.albumTime))
                .map(entry => ({
                    title: entry.name, url: '/album/' + entry.id, albumId: entry.id
                }));
        }).then((entries: MenuEntry[]) => {
            this.ngZone.run(() => {
                this.appPages = entries;
                const path = window.location.pathname;
                if (path !== undefined) {
                    this.selectedIndex = this.appPages.findIndex(page => page.url === path);
                }
            });
        });
    }

    authenticate() {

    }
}
