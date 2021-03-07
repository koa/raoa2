import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../service/server-api.service';
import {Album, CreateGroupGQL, Group, ManageTeamsListAllGroupsGQL, User} from '../generated/graphql';
import {LoadingController} from '@ionic/angular';

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
                private createGroupGQL: CreateGroupGQL,
                private manageTeamsListAllGroupsGQL: ManageTeamsListAllGroupsGQL,
                private loadController: LoadingController,
                private ngZone: NgZone) {
    }

    async ngOnInit() {
        this.allGroups = (await this.serverApi.query(this.manageTeamsListAllGroupsGQL, {})).listGroups;
        this.refreshFilter();
    }

    updateFilter($event: CustomEvent) {
        this.groupFilter = $event.detail.value.toLowerCase();
        this.refreshFilter();
    }

    private refreshFilter() {
        this.filteredGroups = this.allGroups.filter(g => g.name.toLowerCase().indexOf(this.groupFilter) >= 0);
    }
}
