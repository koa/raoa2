import {NgModule} from '@angular/core';
import {PreloadAllModules, RouterModule, Routes} from '@angular/router';
import {WelcomeComponent} from './redirect-start/welcome.component';
import {AuthGuard, hasLogin, isAuthenticated} from './auth-guard.service';

const routes: Routes = [
    {
        path: '',
        redirectTo: 'album',
        pathMatch: 'full'
    },
    {
        canActivate: [isAuthenticated],
        path: 'welcome',
        component: WelcomeComponent,
        pathMatch: 'full'
    },
    {
        canActivate: [hasLogin],
        path: 'album',
        loadChildren: () => import('./album/album.module').then(m => m.AlbumPageModule)
    },
    {
        canActivate: [hasLogin],
        path: 'process-pending-requests',
        loadChildren: () => import('./process-pending-requests/process-pending-requests.module')
            .then(m => m.ProcessPendingRequestsPageModule)
    },
    {
        canActivate: [hasLogin],
        path: 'manage-users',
        loadChildren: () => import('./manage-users/manage-users.module').then(m => m.ManageUsersPageModule)
    },
    {
        canActivate: [hasLogin],
        path: 'manage-teams',
        loadChildren: () => import('./manage-teams/manage-teams.module').then(m => m.ManageTeamsPageModule)
    },
    {
        canActivate: [hasLogin],
        path: 'import',
        loadChildren: () => import('./import/import.module').then(m => m.ImportPageModule)
    },
    {
        canActivate: [hasLogin],
        path: 'sync',
        loadChildren: () => import('./sync/sync.module').then(m => m.SyncPageModule)
    },
    {
        path: 'login',
        loadChildren: () => import('./login/login.module').then(m => m.LoginPageModule)
    },
    {
        canActivate: [hasLogin],
        path: 'svps',
        loadChildren: () => import('./svps/svps.module').then(m => m.SvpsPageModule)
    },
    {
        canActivate: [hasLogin],
        path: 'presentation',
        loadChildren: () => import('./presentation/presentation.module').then(m => m.PresentationPageModule)
    }
];

@NgModule({
    imports: [
        RouterModule.forRoot(routes, {
            preloadingStrategy: PreloadAllModules,
            useHash: false,
            enableTracing: false
        })
    ],
    exports: [RouterModule]
})
export class AppRoutingModule {
}
