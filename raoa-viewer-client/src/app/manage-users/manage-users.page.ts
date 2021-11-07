import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../service/server-api.service';
import {ManageUsersOverviewGQL, User} from '../generated/graphql';
import {MenuController} from '@ionic/angular';

@Component({
    selector: 'app-manage-users',
    templateUrl: './manage-users.page.html',
    styleUrls: ['./manage-users.page.scss'],
})
export class ManageUsersPage implements OnInit {
    public userlist: Array<User>;
    public filteredUserList: Array<User> = [];
    private userFilter = '';

    constructor(private serverApi: ServerApiService,
                private manageUsersOverviewGQL: ManageUsersOverviewGQL,
                private ngZone: NgZone,
                private menuController: MenuController
    ) {
    }

    async ngOnInit() {
        const usersList = await this.serverApi.query(this.manageUsersOverviewGQL, {});
        const sortedList = usersList.listUsers.slice().sort((u1, u2) => u1.info.name.localeCompare(u2.info.name));
        this.ngZone.run(() => {
            this.userlist = sortedList as Array<User>;
            this.updateFilter();
        });
    }

    updateSearch($event: CustomEvent) {
        this.userFilter = $event.detail.value.toLowerCase();
        this.updateFilter();
    }

    private updateFilter() {
        this.filteredUserList = this.userlist.filter(user => user.info.name.toLowerCase().indexOf(this.userFilter) >= 0
            || user.info.email.toLowerCase().indexOf(this.userFilter) >= 0);
    }

    public openNavigationMenu(): Promise<void> {
        return this.menuController.open('navigation').then();
    }
}
