import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {ManageUsersPageRoutingModule} from './manage-users-routing.module';

import {ManageUsersPage} from './manage-users.page';
import {EditUserComponent} from './edit-user/edit-user.component';
import {MainMenuComponentModule} from '../main-menu/main-menu.module';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        ManageUsersPageRoutingModule,
        MainMenuComponentModule
    ],
    exports: [],
    declarations: [ManageUsersPage, EditUserComponent]
})
export class ManageUsersPageModule {
}
