import {Injectable} from '@angular/core';
import {Album, SyncCreatePasswordGQL, SyncCreatePasswordMutation, SyncCreatePasswordMutationVariables} from '../generated/graphql';
import {quote} from 'shell-quote';
import {ServerApiService} from './server-api.service';

type AlbumSyncData = { __typename?: 'Album' } & Pick<Album, 'id' | 'albumPath'>;

@Injectable({
    providedIn: 'root'
})
export class SyncService {

    constructor(private serverApiService: ServerApiService,
                private syncCreatePasswordGQL: SyncCreatePasswordGQL) {
    }

    public async createTemporaryPassword(): Promise<string> {
        const pwResult = await this.serverApiService.update<SyncCreatePasswordMutation, SyncCreatePasswordMutationVariables>(
            this.syncCreatePasswordGQL, {}
        );
        return pwResult.createTemporaryPassword.password;
    }

    public createBaseDir(user: string, password: string): URL {
        const baseDir: URL = new URL(window.location.origin + '/git');
        baseDir.username = user;
        baseDir.password = password;
        return baseDir;
    }

    public createParentDirCmd(album: AlbumSyncData, createdParentPath?: Set<string>): string {
        const parentPath: string = quote([album.albumPath.slice(0, -1).join('/')]);
        if (createdParentPath) {
            if (createdParentPath.has(parentPath)) {
                return '';
            }
            createdParentPath.add(parentPath);
        }
        return `[ -d ${parentPath} ] || mkdir -p ${parentPath}
`;
    }

    public gitFetchCmd(album: AlbumSyncData, baseDir: URL): string {
        return `git fetch ${baseDir}/${album.id} master:master`;
    }

    public gitCloneBareCmd(album: AlbumSyncData, baseDir: URL): string {
        const targetName = quote([album.albumPath[album.albumPath.length - 1] + '.git']);
        return `git clone --bare ${baseDir}/${album.id} ${targetName}`;
    }

    public gitSyncBareBareCmd(album: AlbumSyncData, baseDir: URL): string {
        const parentPath: string = quote([album.albumPath.slice(0, -1).join('/')]);
        const targetPath = quote([album.albumPath.join('/') + '.git']);
        const targetName = quote([album.albumPath[album.albumPath.length - 1] + '.git']);
        return `if [ -d ${targetPath} ] ; then
(
    cd ${targetPath}
    ${(this.gitFetchCmd(album, baseDir))}
)
else
(
    cd ${parentPath}
    ${(this.gitCloneBareCmd(album, baseDir))}
)
fi ${(this.errorCollectingCommandSuffix(targetPath))}
`;

    }

    public errorCollectingPrefix(): string {
        return 'errors=()';
    }

    public errorCollectingCommandSuffix(errorId: string) {
        return `||errors+=(${errorId})`;
    }

    public errorCollectingSuffix(): string {
        return `if [ $\{#errors[@]} -gt 0 ]; then
    echo "---------------------------"
    echo Error found on repositories
    echo "---------------------------"
    for repo in "$\{errors[@]}"; do
        echo "  " $repo
    done
    echo "---------------------------"
fi`;
    }
}
