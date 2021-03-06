import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {ManageUsersPageRoutingModule} from './manage-users-routing.module';

import {ManageUsersPage} from './manage-users.page';
import {EditUserComponent} from './edit-user/edit-user.component';
import {SuperTabsModule} from '@ionic-super-tabs/angular';
import {GroupListEditorComponent} from '../group-list-editor/group-list-editor.component';
import {AlbumListEditorComponent} from '../album-list-editor/album-list-editor.component';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        ManageUsersPageRoutingModule,
        SuperTabsModule
    ],
    exports: [
        GroupListEditorComponent,
        AlbumListEditorComponent
    ],
    declarations: [ManageUsersPage, EditUserComponent, GroupListEditorComponent, AlbumListEditorComponent]
})
export class ManageUsersPageModule {
}
