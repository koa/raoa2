import {Component, EventEmitter, Input, NgZone, OnInit, Output} from '@angular/core';
import {
    Maybe,
    User,
    UserEditorListAllUsersGQL,
    UserEditorListAllUsersQuery,
    UserEditorListAllUsersQueryVariables,
    UserInfo
} from '../generated/graphql';
import {ServerApiService} from '../service/server-api.service';


type UserDataType =
    { __typename?: 'User' }
    & Pick<User, 'id'>
    & { info?: Maybe<{ __typename?: 'UserInfo' } & Pick<UserInfo, 'name' | 'email' | 'picture'>> };


@Component({
    selector: 'app-user-list-editor',
    templateUrl: './user-list-editor.component.html',
    styleUrls: ['./user-list-editor.component.scss'],
})
export class UserListEditorComponent implements OnInit {
    public users: UserDataType[] = [];
    public filteredUsers: UserDataType[] = [];
    @Input('selectedUsers') public selectedUsers: Set<string> = new Set();
    @Output()
    public valueChanged = new EventEmitter<Set<string>>();
    private userFilter = '';

    constructor(private serverApi: ServerApiService,
                private userEditorListAllUsersGQL: UserEditorListAllUsersGQL,
                private ngZone: NgZone
    ) {
    }

    async ngOnInit() {
        await this.refreshData();
    }

    private async refreshData() {
        const data = await this.serverApi.query<UserEditorListAllUsersQuery, UserEditorListAllUsersQueryVariables>(
            this.userEditorListAllUsersGQL, {}
        );
        this.ngZone.run(() => {
            this.users = data.listUsers.slice().sort((u1, u2) => u1.info.name.localeCompare(u2.info.name));
            this.filterUser();
        });
    }

    public searchUser($event: CustomEvent) {
        this.userFilter = $event.detail.value;
        this.filterUser();
    }

    private filterUser() {
        const pattern = this.userFilter.toLowerCase();
        this.filteredUsers = this.users
            .filter(e => e.info.name.toLowerCase().indexOf(pattern) >= 0 || e.info.email.toLowerCase().indexOf(pattern) >= 0);
    }

    public toggle(id: string) {
        if (this.selectedUsers.has(id)) {
            this.selectedUsers.delete(id);
        } else {
            this.selectedUsers.add(id);
        }
        this.valueChanged.emit(this.selectedUsers);
    }
}
