import {NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {ManageUsersPageRoutingModule} from './manage-users-routing.module';

import {ManageUsersPage} from './manage-users.page';
import {EditUserComponent} from './edit-user/edit-user.component';
import {SuperTabsModule} from '@ionic-super-tabs/angular';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        ManageUsersPageRoutingModule,
        SuperTabsModule
    ],
    exports: [],
    declarations: [ManageUsersPage, EditUserComponent]
})
export class ManageUsersPageModule {
}
