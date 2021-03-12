import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../service/server-api.service';
import {Album, Group, ManageGroupsCreateGroupGQL, ManageTeamsListAllGroupsGQL, User} from '../generated/graphql';
import {IonInput, LoadingController} from '@ionic/angular';

type GroupEntry = { __typename?: 'Group' } & Pick<Group, 'id' | 'name'> &
    {
        canAccess: Array<{ __typename?: 'Album' } & Pick<Album, 'id'>>;
        members: Array<{ __typename?: 'UserMembership' } & { user: { __typename?: 'User' } & Pick<User, 'id'> }>
    };

@Component({
    selector: 'app-manage-teams',
    templateUrl: './manage-teams.page.html',
    styleUrls: ['./manage-teams.page.scss'],
})
export class ManageTeamsPage implements OnInit {
    private groupFilter = '';
    public filteredGroups: GroupEntry[] = [];
    private allGroups: Array<GroupEntry> = [];

    constructor(private serverApi: ServerApiService,
                private createGroupGQL: ManageGroupsCreateGroupGQL,
                private manageTeamsListAllGroupsGQL: ManageTeamsListAllGroupsGQL,
                private loadController: LoadingController,
                private ngZone: NgZone) {
    }

    async ngOnInit() {
        await this.refreshData();
    }

    private async refreshData() {
        this.allGroups = (await this.serverApi.query(this.manageTeamsListAllGroupsGQL, {})).listGroups;
        this.sortGroups();
        this.refreshFilter();
    }

    private sortGroups() {
        this.allGroups = this.allGroups.slice().sort((g1, g2) => g1.name.localeCompare(g2.name));
    }

    updateFilter($event: CustomEvent) {
        this.groupFilter = $event.detail.value.toLowerCase();
        this.refreshFilter();
    }

    private refreshFilter() {
        this.filteredGroups = this.allGroups.filter(g => g.name.toLowerCase().indexOf(this.groupFilter) >= 0);
    }


    async createGroup($event: KeyboardEvent) {
        const loadingElement = await this.loadController.create({message: 'Gruppe erstellen'});
        await loadingElement.present();
        const input = ($event.target as unknown) as IonInput;
        const groupName = input.value;
        const result = await this.serverApi.update(this.createGroupGQL, {name: groupName});
        await loadingElement.dismiss();
        await this.refreshData();
        this.ngZone.run(() => {
            input.value = '';
        });
    }

    getGroupName(group: Group) {
        return group.name;
    }
}
