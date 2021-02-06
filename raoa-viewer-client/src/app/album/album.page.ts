import {Component, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {AppConfigService} from '../service/app-config.service';
import {LoginService} from '../service/login.service';

@Component({
    selector: 'app-album',
    templateUrl: './album.page.html',
    styleUrls: ['./album.page.scss'],
})
export class AlbumPage implements OnInit {
    public albumId: string;

    constructor(private activatedRoute: ActivatedRoute, private appConfig: AppConfigService, private login: LoginService) {
    }

    ngOnInit() {
        this.albumId = this.activatedRoute.snapshot.paramMap.get('id');
        this.login.auth().then(a => console.log(a.currentUser.get().getBasicProfile().getName()));
    }


}
