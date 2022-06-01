import {NgModule} from '@angular/core';
import {Routes, RouterModule} from '@angular/router';

import {SvpsPage} from './svps.page';

const routes: Routes = [
    {
        path: '',
        component: SvpsPage
    },
    {
        path: 'competition/:competitionId',
        loadChildren: () => import('./competition/competition.module').then(m => m.CompetitionPageModule)
    }
];

@NgModule({
    imports: [RouterModule.forChild(routes)],
    exports: [RouterModule],
})
export class SvpsPageRoutingModule {
}
