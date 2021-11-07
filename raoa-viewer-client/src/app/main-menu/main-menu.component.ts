import {Component, NgZone, OnInit} from '@angular/core';
import {DataService, UserPermissions} from '../service/data.service';

@Component({
    selector: 'app-main-menu',
    templateUrl: './main-menu.component.html',
    styleUrls: ['./main-menu.component.scss'],
})
export class MainMenuComponent implements OnInit {
    public userPermissions: UserPermissions | undefined;

    constructor(
        private dataService: DataService,
        private ngZone: NgZone
    ) {
    }

    async ngOnInit() {
        const userPermissions = await this.dataService.userPermission();
        this.ngZone.run(() => this.userPermissions = userPermissions);
    }

}
