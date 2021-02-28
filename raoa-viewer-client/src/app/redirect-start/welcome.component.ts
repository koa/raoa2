import {Component, NgZone, OnInit} from '@angular/core';
import {Router} from '@angular/router';
import {MenuController} from '@ionic/angular';
import {CommonServerApiService} from '../service/common-server-api.service';

@Component({
    selector: 'app-redirect-start',
    templateUrl: './welcome.component.html',
    styleUrls: ['./welcome.component.scss'],
})
export class WelcomeComponent implements OnInit {

    public totalPhotoCount: number;

    constructor(private router: Router,
                private menu: MenuController,
                private commonServerApiService: CommonServerApiService,
                private ngZone: NgZone) {
    }

    ngOnInit() {
        const redirectRoute = sessionStorage.getItem('redirect_route');
        if (redirectRoute !== null) {
            sessionStorage.removeItem('redirect_route');
            this.router.navigateByUrl(redirectRoute);
        }
        this.commonServerApiService.listCollections().then(list => {
            this.ngZone.run(() => this.totalPhotoCount = list.reduce((sum, e) => e.data.entryCount + sum, 0));
        });
    }

    openMenu() {
        this.menu.open();
    }
}
