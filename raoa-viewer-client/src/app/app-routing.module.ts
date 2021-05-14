import {NgModule} from '@angular/core';
import {PreloadAllModules, RouterModule, Routes} from '@angular/router';
import {WelcomeComponent} from './redirect-start/welcome.component';

const routes: Routes = [
    {
        path: '',
        component: WelcomeComponent,
        pathMatch: 'full'
    },
    {
        path: 'album',
        loadChildren: () => import('./album/album.module').then(m => m.AlbumPageModule)
    },
    {
        path: 'process-pending-requests',
        loadChildren: () => import('./process-pending-requests/process-pending-requests.module')
            .then(m => m.ProcessPendingRequestsPageModule)
    },
    {
        path: 'manage-users',
        loadChildren: () => import('./manage-users/manage-users.module').then(m => m.ManageUsersPageModule)
    },
  {
    path: 'manage-teams',
    loadChildren: () => import('./manage-teams/manage-teams.module').then( m => m.ManageTeamsPageModule)
  },
  {
    path: 'import',
    loadChildren: () => import('./import/import.module').then( m => m.ImportPageModule)
  },
  {
    path: 'sync',
    loadChildren: () => import('./sync/sync.module').then( m => m.SyncPageModule)
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
