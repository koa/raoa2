import {NgModule} from '@angular/core';
import {RouterModule, Routes} from '@angular/router';

import {ManageTeamsPage} from './manage-teams.page';
import {EditGroupComponent} from './edit-group/edit-group.component';

const routes: Routes = [
    {
        path: '',
        component: ManageTeamsPage
    }, {
        path: ':id',
        component: EditGroupComponent
    }
];

@NgModule({
    imports: [RouterModule.forChild(routes)],
    exports: [RouterModule],
})
export class ManageTeamsPageRoutingModule {
}
