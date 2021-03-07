import {Component, EventEmitter, Input, NgZone, OnInit, Output} from '@angular/core';
import {CreateGroupGQL, Group, GroupEditorListAllGroupsGQL, UpdateCredentitalsGQL} from '../generated/graphql';
import {ServerApiService} from '../service/server-api.service';
import {LoadingController} from '@ionic/angular';

type GroupDataType = { __typename?: 'Group' } & Pick<Group, 'id' | 'name'>;

@Component({
    selector: 'app-group-list-editor',
    templateUrl: './group-list-editor.component.html',
    styleUrls: ['./group-list-editor.component.scss'],
})
export class GroupListEditorComponent implements OnInit {
    public groups: GroupDataType[] = [];
    public filteredGroups: GroupDataType[] = [];
    public newGroupName = '';
    private groupFilter = '';
    @Input('selectedGroups')
    public selectedGroups: Set<string> = new Set();
    @Output()
    valueChanged = new EventEmitter<Set<string>>();

    constructor(private serverApi: ServerApiService,
                private createGroupGQL: CreateGroupGQL,
                private groupEditorListAllGroupsGQL: GroupEditorListAllGroupsGQL,
                private loadController: LoadingController,
                private ngZone: NgZone
    ) {
    }

    async ngOnInit() {
        await this.refreshData();
    }

    searchGroup($event: CustomEvent) {
        this.groupFilter = $event.detail.value;
        this.filterGroup();

    }

    updateNewGroupName($event: CustomEvent) {
        this.newGroupName = $event.detail.value;
    }

    async createNewGroup() {
        const groupName = this.newGroupName;
        if (groupName.length === 0) {
            return;
        }
        if (this.groups.filter(g => g.name === groupName).length > 0) {
            return;
        }
        this.newGroupName = '';
        const loadingElement = await this.loadController.create({message: 'Erstelle Gruppe ' + groupName});
        await loadingElement.present();
        const result = await this.serverApi.update(this.createGroupGQL, {name: groupName});
        await this.serverApi.clear();
        await loadingElement.dismiss();
        await this.refreshData();
    }


    private filterGroup() {
        const pattern = this.groupFilter.toLowerCase();
        this.filteredGroups = this.groups
            .filter(e => e.name.toLowerCase().indexOf(pattern) >= 0);
    }

    private async refreshData() {
        const data = await this.serverApi.query(this.groupEditorListAllGroupsGQL, {});
        this.ngZone.run(() => {
            this.groups = data.listGroups;
            this.filterGroup();
        });
    }

    toggle(id: string) {
        if (this.selectedGroups.has(id)) {
            this.selectedGroups.delete(id);
        } else {
            this.selectedGroups.add(id);
        }
        this.valueChanged.emit(this.selectedGroups);
    }
}
