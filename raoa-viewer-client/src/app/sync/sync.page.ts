import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../service/server-api.service';
import {SyncCreatePasswordGQL, SyncGetCurrentUserGQL} from '../generated/graphql';

@Component({
    selector: 'app-sync',
    templateUrl: './sync.page.html',
    styleUrls: ['./sync.page.scss'],
})
export class SyncPage implements OnInit {
    public password: string;
    public userId: string;

    constructor(private serverApiService: ServerApiService, private syncCreatePasswordGQL: SyncCreatePasswordGQL, private syncGetCurrentUserGQL: SyncGetCurrentUserGQL, private ngZone: NgZone) {
    }

    async ngOnInit() {
        const pwResult = await this.serverApiService.update(this.syncCreatePasswordGQL, {});
        const uResult = await this.serverApiService.query(this.syncGetCurrentUserGQL, {});
        this.ngZone.run(() => {
            this.userId = uResult.currentUser.id;
            this.password = pwResult.createTemporaryPassword.password;
        });
    }

}
