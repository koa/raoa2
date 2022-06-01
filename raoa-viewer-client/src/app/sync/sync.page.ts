import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../service/server-api.service';
import {SyncQueryDataForSyncGQL, SyncQueryDataForSyncQuery, SyncQueryDataForSyncQueryVariables} from '../generated/graphql';
import {CommonServerApiService} from '../service/common-server-api.service';
import {SyncService} from '../service/sync.service';

@Component({
    selector: 'app-sync',
    templateUrl: './sync.page.html',
    styleUrls: ['./sync.page.scss'],
})
export class SyncPage implements OnInit {

    public template: string;

    constructor(private serverApiService: ServerApiService,
                private syncService: SyncService,
                private queryDataForSyncGQL: SyncQueryDataForSyncGQL,
                private commonServerApiService: CommonServerApiService,
                private ngZone: NgZone) {
    }

    async ngOnInit() {
        const password = await this.syncService.createTemporaryPassword();
        const uResult = await this.serverApiService.query<SyncQueryDataForSyncQuery, SyncQueryDataForSyncQueryVariables>(
            this.queryDataForSyncGQL, {}
        );
        this.ngZone.run(() => {
            const baseDir = this.syncService.createBaseDir(uResult.currentUser.id, password);
            this.template = this.syncService.errorCollectingPrefix() + '\n';
            const checkedParentPath = new Set<string>();
            for (const album of uResult.listAlbums) {
                this.template += this.syncService.createParentDirCmd(album, checkedParentPath);
                this.template += this.syncService.gitSyncBareBareCmd(album, baseDir);
            }
            this.template += this.syncService.errorCollectingSuffix();
        });
    }

    public canCopyToClipboard(): boolean {
        return this.template && navigator.clipboard !== undefined && this.template.length > 0;
    }

    public async copyToClipboard() {
        await navigator.clipboard.writeText(this.template);
    }
}
