import {Component, NgZone, OnInit} from '@angular/core';
import {
    Album,
    AlbumEntry,
    ImportCommitGQL,
    ImportCommitMutation,
    ImportCommitMutationVariables,
    ImportCreateAlbumGQL,
    ImportCreateAlbumMutation,
    ImportCreateAlbumMutationVariables,
    ImportedFile,
    ImportFile,
    ImportIdentifyFileGQL,
    ImportIdentifyFileQuery,
    ImportIdentifyFileQueryVariables,
    ImportListAlbumGQL,
    ImportListAlbumQuery,
    ImportListAlbumQueryVariables,
    ImportSetAutoaddGQL,
    Maybe
} from '../generated/graphql';
import {ServerApiService} from '../service/server-api.service';
import {LoadingController, MenuController, ToastController} from '@ionic/angular';
import {HttpClient, HttpEvent, HttpEventType} from '@angular/common/http';
import {StorageService, UploadedFileEntry} from '../service/storage.service';
import {DataService} from '../service/data.service';

interface UploadResult {
    byteCount: number;
    fileId: string;
}

interface ImportResult {
    file: FileSystemFileHandle;
    directory: FileSystemDirectoryHandle;
    createdEntry: (
        { __typename?: 'ImportedFile' }
        & Pick<ImportedFile, 'fileId'>
        & {
        albumEntry?: Maybe<(
            { __typename?: 'AlbumEntry' }
            & Pick<AlbumEntry, 'id'>
            & {
            album?: Maybe<(
                { __typename?: 'Album' }
                & Pick<Album, 'id' | 'name'>
                )>
        })>
    });
}


interface ImportStatisticsEntry {
    albumId: string;
    albumName: string;
    uploaded: number;
    committed: number;
    albumTime: number;
}

interface ImportAlbumState {
    autoCommit: boolean;
    runningCommit: boolean;
    scheduler: number | undefined;
}


const uploadFileSuffix: Set<string> = new Set<string>(['jpg', 'nef', 'ts', 'mp4', 'mkv']);


const READ_PERMISSION: FileSystemHandlePermissionDescriptor = {mode: 'read'};
const DELETE_PERMISSION: FileSystemHandlePermissionDescriptor = {mode: 'readwrite'};

@Component({
    selector: 'app-import',
    templateUrl: './import.page.html',
    styleUrls: ['./import.page.scss'],
})
export class ImportPage implements OnInit {

    public uploadOverallProgress = 0;
    public parentCandidates: string[] = [];
    public newAlbumParent = '';
    public newAlbumName = '';
    public newAlbumTimestamp = '';
    public currentFileName = '';
    public uploadFileProgress = 0;
    public importedFiles: ImportResult[] = [];
    public uploadedStatistics: Map<string, ImportStatisticsEntry> = new Map<string, ImportStatisticsEntry>();
    public currentAlbumStates: Map<string, ImportAlbumState> = new Map<string, ImportAlbumState>();
    public canImportFiles = window.showOpenFilePicker !== undefined;
    public uploadedStatisticsList: ImportStatisticsEntry[] = [];
    private identifiedAlbums: Map<string, string> = new Map<string, string>();
    private updateAutocommitTimer: number = undefined;
    public pendingHiddenUploads = 0;

    constructor(private importListAlbumGQL: ImportListAlbumGQL,
                private importCreateAlbumGQL: ImportCreateAlbumGQL,
                private importSetAutoaddGQL: ImportSetAutoaddGQL,
                private importIdentifyFileGQL: ImportIdentifyFileGQL,
                private importCommitGQL: ImportCommitGQL,
                private serverApiService: ServerApiService,
                private httpClient: HttpClient,
                private toastController: ToastController,
                private loadingController: LoadingController,
                private menuController: MenuController,
                private storageService: StorageService,
                private dataService: DataService,
                private ngZone: NgZone) {
    }

    async ngOnInit() {
        const albumData = await this.serverApiService.query<ImportListAlbumQuery, ImportListAlbumQueryVariables>(
            this.importListAlbumGQL, {}
        );
        if (albumData?.listAlbums) {
            const parentPaths = new Set<string>();
            parentPaths.add('');
            for (const album of albumData.listAlbums) {
                const path = album.albumPath;
                if (path.length > 0) {
                    parentPaths.add(path.slice(0, path.length - 1).join('/'));
                }
            }
            parentPaths.forEach(v => this.parentCandidates.push(v));
            this.parentCandidates.sort((v1, v2) => v1.localeCompare(v2));
        }
        await this.updateUploadStats();
    }

    async selectDirectory() {
        const directoryHandle: FileSystemDirectoryHandle = await showDirectoryPicker();

        const wait = await this.loadingController.create({message: 'Analysiere', duration: 120000});
        await wait.present();
        try {
            await this.updateUploadStats();
            const filesToUpload: [FileSystemFileHandle, FileSystemDirectoryHandle][] = [];
            await this.processDirectory(directoryHandle, filesToUpload);
            let totalSize = 0;
            for (const item of filesToUpload) {
                totalSize += (await item[0].getFile()).size;
            }
            let uploadedSize = 0;
            const results: Promise<void>[] = [];
            const alreadyUploadedFiles: UploadedFileEntry[] = [];
            await this.storageService.processUploadedFiles(uploadedFile => {
                alreadyUploadedFiles.push(uploadedFile);
            });
            const identifiedFiles: File[] = [];
            for (const alreadyUploadedFile of alreadyUploadedFiles) {
                if (await alreadyUploadedFile.sourceFile.queryPermission(READ_PERMISSION) === 'granted') {
                    identifiedFiles.push(await alreadyUploadedFile.sourceFile.getFile());
                }
            }
            await wait.dismiss();
            nextFile: for (const item of filesToUpload) {
                const data = await item[0].getFile();
                for (const identifiedFile of identifiedFiles) {
                    if (identifiedFile.name === data.name &&
                        identifiedFile.size === data.size &&
                        identifiedFile.lastModified === data.lastModified) {
                        continue nextFile;
                    }
                }
                this.ngZone.run(() => this.currentFileName = item[0].name);
                const result = await new Promise<UploadResult>((resolve, reject) => {
                    this.httpClient.post('/rest/import', data, {reportProgress: true, observe: 'events'})
                        .subscribe((event: HttpEvent<UploadResult>) => {
                            switch (event.type) {
                                case HttpEventType.Response:
                                    resolve(event.body);
                                    break;
                                case HttpEventType.UploadProgress:
                                    this.ngZone.run(() => {
                                        this.uploadFileProgress = event.loaded / event.total;
                                        this.uploadOverallProgress = (event.loaded + uploadedSize) / totalSize;
                                    });
                                    break;
                            }
                        }, error => reject(error));
                });
                if (data.size === result.byteCount) {
                    const postProcess = async () => {
                        uploadedSize += data.size;
                        this.ngZone.run(() => this.uploadOverallProgress = uploadedSize / totalSize);
                        const identifiedFile: UploadedFileEntry = {
                            fileId: result.fileId,
                            sourceDirectory: item[1],
                            sourceFile: item[0],
                            size: result.byteCount,
                            committed: false
                        };
                        await this.storageService.storeUploadedFileEntry([identifiedFile]);
                        await this.updateUploadStats();

                    };
                    results.push(postProcess());
                }
            }
            await Promise.all(results);
            this.ngZone.run(() => {
                this.currentFileName = '';
                this.uploadOverallProgress = 0;
                this.uploadFileProgress = 0;
            });
        } finally {
            await wait.dismiss();
        }
    }

    private async updateUploadStats() {
        const allUploadedFiles: UploadedFileEntry[] = [];
        await this.storageService.processUploadedFiles(async statEntry => {
            allUploadedFiles.push(statEntry);
        });
        const statPerAlbum: Map<string, ImportStatisticsEntry> = new Map<string, ImportStatisticsEntry>();
        for (const uploadedFile of allUploadedFiles) {
            let albumId;
            if (this.identifiedAlbums.has(uploadedFile.fileId)) {
                albumId = this.identifiedAlbums.get(uploadedFile.fileId);
            } else {
                const currentState = await uploadedFile.sourceDirectory.queryPermission(READ_PERMISSION);
                if (currentState !== 'granted') {
                    continue;
                }
                try {
                    const file = await uploadedFile.sourceFile.getFile();
                    const previewImport = (await this.serverApiService.query<ImportIdentifyFileQuery, ImportIdentifyFileQueryVariables>(
                        this.importIdentifyFileGQL, {
                            file: {
                                fileId: uploadedFile.fileId,
                                size: uploadedFile.size,
                                filename: file.name
                            }
                        }))?.previewImport;
                    albumId = previewImport?.id;
                    this.identifiedAlbums.set(uploadedFile.fileId, albumId);
                    if (!albumId) {
                        const errorMsg = await this.toastController.create({
                            message: 'Error identify file ' + file.name,
                            color: 'warning',
                            duration: 10000
                        });
                        await errorMsg.present();
                    }
                } catch (e) {
                    console.log('Error on file', uploadedFile.sourceFile.name, e);
                }
            }
            if (albumId) {
                let statOfAlbum: ImportStatisticsEntry;
                if (statPerAlbum.has(albumId)) {
                    statOfAlbum = statPerAlbum.get(albumId);
                } else {
                    const albumData = await this.dataService.getAlbum(albumId);
                    statOfAlbum = {
                        albumId, uploaded: 0, committed: 0, albumName: albumData.title, albumTime: albumData.albumTime
                    };
                    statPerAlbum.set(albumId, statOfAlbum);
                }
                if (uploadedFile.committed) {
                    statOfAlbum.committed += 1;
                } else {
                    statOfAlbum.uploaded += 1;
                }
            }
        }


        this.ngZone.run(() => {
            this.uploadedStatisticsList = [];
            statPerAlbum.forEach(value => {
                if (!this.currentAlbumStates.has(value.albumId)) {
                    this.currentAlbumStates.set(value.albumId, {autoCommit: false, runningCommit: false, scheduler: 0});
                }
                this.uploadedStatisticsList.push(value);
                this.uploadedStatisticsList.sort((e1, e2) => {
                    return e2.albumTime - e1.albumTime;
                });
            });

        });
    }

    private async processDirectory(directory: FileSystemDirectoryHandle,
                                   filesToUpload: [FileSystemFileHandle, FileSystemDirectoryHandle][]) {
        for await (const [name, containingFile] of directory.entries()) {
            if (containingFile instanceof FileSystemDirectoryHandle) {
                await this.processDirectory(containingFile, filesToUpload);
            } else if (containingFile instanceof FileSystemFileHandle) {
                const lastPt = name.lastIndexOf('.');
                if (lastPt > 0) {
                    const suffix = name.substr(lastPt + 1).toLowerCase();
                    if (uploadFileSuffix.has(suffix)) {
                        filesToUpload.push([containingFile, directory]);
                    }
                }
            }
        }
    }


    public async createAlbum(): Promise<void> {
        const path: string[] = [];
        this.appendPath(this.newAlbumParent, path);
        this.appendPath(this.newAlbumName, path);
        if (path.length === 0) {
            return;
        }
        const result = await this.serverApiService.update<ImportCreateAlbumMutation, ImportCreateAlbumMutationVariables>(
            this.importCreateAlbumGQL, {path}
        );
        const id = result.createAlbum.id;
        if (this.newAlbumTimestamp) {
            await this.serverApiService.update(this.importSetAutoaddGQL, {id, date: this.newAlbumTimestamp});
        }
        const toastElement = await this.toastController.create({message: 'Album ' + id + ' erstellt', duration: 10});
        await toastElement.present();
    }

    private appendPath(newComps: string, path: string[]) {
        newComps.split('/').map(e => e.trim()).filter(e => e.length > 0).forEach(v => path.push(v));
    }

    async deleteImportedFiles(): Promise<void> {
        for (const fileEntry of this.importedFiles) {
            await fileEntry.directory.removeEntry(fileEntry.file.name);
            const value = fileEntry.createdEntry.fileId;
            this.importedFiles = this.importedFiles.filter(entry => entry.createdEntry.fileId === value);
            // this.updateImportStats();
        }
    }

    async commit(albumId: string, maxSize: number): Promise<boolean> {
        const albumState = this.currentAlbumStates.get(albumId);
        if (!albumState || albumState.runningCommit) {
            return false;
        }
        try {
            albumState.runningCommit = true;
            let takenSize = 0;
            const filesOfAlbum: UploadedFileEntry[] = [];
            await this.storageService.processUploadedFiles(uploadedFile => {
                if (this.identifiedAlbums.get(uploadedFile.fileId) === albumId && !uploadedFile.committed) {
                    filesOfAlbum.push(uploadedFile);
                }
            });
            const files: ImportFile[] = [];
            for (const uploadedFile of filesOfAlbum) {
                const file = await uploadedFile.sourceFile.getFile();
                const nextSize = takenSize + file.size;
                if (takenSize > 0 && nextSize > maxSize) {
                    break;
                }
                files.push({fileId: uploadedFile.fileId, filename: uploadedFile.sourceFile.name, size: file.size});
                takenSize = nextSize;
            }
            if (files.length === 0) {
                return false;
            }
            const wait = await this.loadingController.create({message: 'Commit', duration: 120000});
            await wait.present();
            try {
                const commitResult = await this.serverApiService.update<ImportCommitMutation, ImportCommitMutationVariables>(
                    this.importCommitGQL, {files}
                );
                const committedFileIds: Set<string> = new Set<string>();
                for (const resultEntry of commitResult.commitImport) {
                    committedFileIds.add(resultEntry.fileId);
                }
                const committedFiles: UploadedFileEntry[] = [];
                for (const uploadedFileEntry of filesOfAlbum) {
                    if (committedFileIds.has(uploadedFileEntry.fileId)) {
                        uploadedFileEntry.committed = true;
                        committedFiles.push(uploadedFileEntry);
                    }
                }
                await this.storageService.storeUploadedFileEntry(committedFiles);
                await this.updateUploadStats();
                return true;
            } finally {
                await wait.dismiss();
            }
        } finally {
            albumState.runningCommit = false;
        }
    }

    async deleteCommitted(albumId: string) {
        const albumState = this.currentAlbumStates.get(albumId);
        const committedFiles: UploadedFileEntry[] = [];
        await this.storageService.processUploadedFiles(async statEntry => {
            if (albumId === this.identifiedAlbums.get(statEntry.fileId) && statEntry.committed === true) {
                committedFiles.push(statEntry);
            }
        });

        if (!albumState || albumState.runningCommit) {
            return;
        }
        try {
            albumState.runningCommit = true;
            const wait = await this.loadingController.create({message: 'Delete', duration: 20 * 60 * 1000});
            const doLoad = async (): Promise<void> => {
                await wait.present();
                const removedFiles: string[] = [];
                while (committedFiles.length > 0) {
                    const fileEntry = committedFiles.pop();
                    await fileEntry.sourceDirectory.removeEntry(fileEntry.sourceFile.name);
                    removedFiles.push(fileEntry.fileId);
                }
                await this.storageService.removeUploadedFiles(removedFiles);
                await wait.dismiss();
            };
            doLoad().finally(() => this.updateUploadStats())
                .catch(error => {
                    console.log(error);
                    this.toastController.create({message: 'Error: ' + error, duration: 10 * 1000})
                        .then(er => er.present());
                });
        } finally {
            albumState.runningCommit = false;
        }
    }

    updateAutoCommit(albumId: string) {
        const statEntry = this.currentAlbumStates.get(albumId);
        if (!statEntry) {
            return;
        }
        const oldTimer = this.updateAutocommitTimer;
        if (oldTimer) {
            window.clearTimeout(oldTimer);
        }
        this.updateAutocommitTimer = window.setTimeout(() => {
            if (statEntry.autoCommit && statEntry.scheduler === 0) {
                statEntry.scheduler = window.setInterval(async () => {
                    while (await this.commit(albumId, 128 * 1024 * 1024)) {
                    }
                }, 1000);
            }
            if (!statEntry.autoCommit && statEntry.scheduler !== 0) {
                window.clearTimeout(statEntry.scheduler);
                statEntry.scheduler = undefined;
            }
            this.updateAutocommitTimer = undefined;
        }, 100);
    }

    public openNavigationMenu(): Promise<void> {
        return this.menuController.open('navigation').then();
    }

    public stateOf(albumId: string): ImportAlbumState {
        return this.currentAlbumStates.get(albumId);
    }

    public async cleanAllPendingUploads() {
        await this.storageService.removeAllUploadedFiles();
        await this.updateUploadStats();
    }
}
