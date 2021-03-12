import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {ManageTeamsPageRoutingModule} from './manage-teams-routing.module';

import {ManageTeamsPage} from './manage-teams.page';
import {EditGroupComponent} from './edit-group/edit-group.component';
import {SuperTabsModule} from '@ionic-super-tabs/angular';
import {ManageUsersPageModule} from '../manage-users/manage-users.module';
import {AlbumPageModule} from '../album/album.module';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        ManageTeamsPageRoutingModule,
        SuperTabsModule,
        ManageUsersPageModule,
        AlbumPageModule
    ],
    declarations: [ManageTeamsPage, EditGroupComponent]
})
export class ManageTeamsPageModule {
}
