import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {ManageTeamsPageRoutingModule} from './manage-teams-routing.module';

import {ManageTeamsPage} from './manage-teams.page';
import {EditGroupComponent} from './edit-group/edit-group.component';
import {ManageUsersPageModule} from '../manage-users/manage-users.module';
import {AlbumPageModule} from '../album/album.module';
import {UserListEditorModule} from '../user-list-editor/user-list-editor.module';
import {AlbumListEditorModule} from '../album-list-editor/album-list-editor.module';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        ManageTeamsPageRoutingModule,
        ManageUsersPageModule,
        AlbumPageModule,
        UserListEditorModule,
        AlbumListEditorModule
    ],
    declarations: [ManageTeamsPage, EditGroupComponent]
})
export class ManageTeamsPageModule {
}
