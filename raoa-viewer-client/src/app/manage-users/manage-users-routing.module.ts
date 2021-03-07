import {NgModule} from '@angular/core';
import {RouterModule, Routes} from '@angular/router';

import {ManageUsersPage} from './manage-users.page';
import {EditUserComponent} from './edit-user/edit-user.component';

const routes: Routes = [
    {
        path: '',
        component: ManageUsersPage
    },
    {
        path: 'user/:id',
        component: EditUserComponent
    }
];

@NgModule({
    imports: [RouterModule.forChild(routes)],
    exports: [RouterModule],
})
export class ManageUsersPageRoutingModule {
}
