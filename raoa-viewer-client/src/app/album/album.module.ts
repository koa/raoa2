import {CUSTOM_ELEMENTS_SCHEMA, NgModule} from '@angular/core';
import {CommonModule} from '@angular/common';
import {FormsModule} from '@angular/forms';

import {IonicModule} from '@ionic/angular';

import {AlbumPageRoutingModule} from './album-routing.module';

import {AlbumPage} from './single-album/album.page';
import {AuthImagePipe} from './pipe/auth-image.pipe';
import {AngularResizeEventModule} from 'angular-resize-event';
import {InViewportDirective} from './in-viewport.directive';
import {ShowSingleMediaComponent} from './show-single-media/show-single-media.component';
import {AlbumListComponent} from './album-list/album-list.component';
import {ManageAlbumComponent} from './manage-album/manage-album.component';
import {ManageUsersPageModule} from '../manage-users/manage-users.module';
import {MainMenuComponentModule} from '../main-menu/main-menu.module';
import {GroupListEditorModule} from '../group-list-editor/group-list-editor.module';
import {UserListEditorModule} from '../user-list-editor/user-list-editor.module';
import {
    SingleAlbumRightPopoverMenuComponent
} from './single-album/single-album-right-popover-menu/single-album-right-popover-menu.component';

@NgModule({
    imports: [
        CommonModule,
        FormsModule,
        IonicModule,
        AlbumPageRoutingModule,
        AngularResizeEventModule,
        ManageUsersPageModule,
        MainMenuComponentModule,
        GroupListEditorModule,
        UserListEditorModule
    ],
    declarations: [AlbumPage,
        AuthImagePipe,
        InViewportDirective,
        ShowSingleMediaComponent,
        AlbumListComponent,
        ManageAlbumComponent,
        SingleAlbumRightPopoverMenuComponent
    ],
    exports: [
        AuthImagePipe
    ], schemas: [CUSTOM_ELEMENTS_SCHEMA]
})
export class AlbumPageModule {
}
