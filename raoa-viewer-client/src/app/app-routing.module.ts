import {NgModule} from '@angular/core';
import {PreloadAllModules, RouterModule, Routes} from '@angular/router';
import {WelcomeComponent} from './redirect-start/welcome.component';
import {AuthGuard} from './auth.guard';

const routes: Routes = [
    {
        canActivate: [AuthGuard],
        path: '',
        component: WelcomeComponent,
        pathMatch: 'full'
    },
    {
        canActivate: [AuthGuard],
        path: 'album',
        loadChildren: () => import('./album/album.module').then(m => m.AlbumPageModule)
    },
    {
        canActivate: [AuthGuard],
        path: 'process-pending-requests',
        loadChildren: () => import('./process-pending-requests/process-pending-requests.module')
            .then(m => m.ProcessPendingRequestsPageModule)
    },
    {
        canActivate: [AuthGuard],
        path: 'manage-users',
        loadChildren: () => import('./manage-users/manage-users.module').then(m => m.ManageUsersPageModule)
    },
    {
        canActivate: [AuthGuard],
        path: 'manage-teams',
        loadChildren: () => import('./manage-teams/manage-teams.module').then(m => m.ManageTeamsPageModule)
    },
    {
        canActivate: [AuthGuard],
        path: 'import',
        loadChildren: () => import('./import/import.module').then(m => m.ImportPageModule)
    },
    {
        canActivate: [AuthGuard],
        path: 'sync',
        loadChildren: () => import('./sync/sync.module').then(m => m.SyncPageModule)
    },
    {
        path: 'login',
        loadChildren: () => import('./login/login.module').then(m => m.LoginPageModule)
    }
];

@NgModule({
    imports: [
        RouterModule.forRoot(routes, {preloadingStrategy: PreloadAllModules, relativeLinkResolution: 'legacy'})
    ],
    exports: [RouterModule]
})
export class AppRoutingModule {
}
