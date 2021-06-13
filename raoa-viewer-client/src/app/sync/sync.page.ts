import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../service/server-api.service';
import {Album, SyncCreatePasswordGQL, SyncQueryDataForSyncGQL} from '../generated/graphql';
import {CommonServerApiService} from '../service/common-server-api.service';
import {quote} from 'shell-quote';


function syncRepoCmd(album: { __typename?: 'Album' } & Pick<Album, 'id' | 'albumPath'>, baseDir: URL, checkedParentPath: Set<string>) {
    const parentPath: string = quote([album.albumPath.slice(0, -1).join('/')]);
    const targetPath = quote([album.albumPath.join('/') + '.git']);
    const targetName = quote([album.albumPath[album.albumPath.length - 1] + '.git']);
    let checkDirPrefix = '\n';
    if (!checkedParentPath.has(parentPath)) {
        if (parentPath.length > 0) {
            checkDirPrefix = `[ -d ${parentPath} ] || mkdir -p ${parentPath}
`;
        }
        checkedParentPath.add(parentPath);
    }

    return checkDirPrefix + `if [ -d ${targetPath} ] ; then
(
    cd ${targetPath}
    git fetch ${baseDir}/${album.id} master:master
)
else
(
    cd ${parentPath}
    git clone --bare ${baseDir}/${album.id} ${targetName}
)
fi ||errors+=(${targetPath})
`;
}

@Component({
    selector: 'app-sync',
    templateUrl: './sync.page.html',
    styleUrls: ['./sync.page.scss'],
})
export class SyncPage implements OnInit {
    public password: string;
    public userId: string;

    public template: string;

    constructor(private serverApiService: ServerApiService,
                private syncCreatePasswordGQL: SyncCreatePasswordGQL,
                private queryDataForSyncGQL: SyncQueryDataForSyncGQL,
                private commonServerApiService: CommonServerApiService,
                private ngZone: NgZone) {
    }

    async ngOnInit() {
        const pwResult = await this.serverApiService.update(this.syncCreatePasswordGQL, {});
        const uResult = await this.serverApiService.query(this.queryDataForSyncGQL, {});
        const baseDir = new URL(window.location.origin + '/git');
        this.ngZone.run(() => {
            this.userId = uResult.currentUser.id;
            this.password = pwResult.createTemporaryPassword.password;
            baseDir.username = this.userId;
            baseDir.password = this.password;
            this.template = 'errors=()\n';
            const checkedParentPath = new Set<string>();
            for (const album of uResult.listAlbums) {
                this.template += syncRepoCmd(album, baseDir, checkedParentPath);
            }
            // language=TEXT
            this.template += 'if [ ${#errors[@]} -gt 0 ]; then\n    echo "---------------------------"\n    echo Error found on repositories\n    echo "---------------------------"\n    for repo in "${errors[@]}"; do\n        echo "  " $repo\n    done\n    echo "---------------------------"\nfi';
        });
    }

    public canCopyToClipboard(): boolean {
        return this.template && navigator.clipboard !== undefined && this.template.length > 0;
    }

    public async copyToClipboard() {
        await navigator.clipboard.writeText(this.template);
    }
}
