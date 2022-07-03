import {Component, NgZone, OnDestroy, OnInit} from '@angular/core';
import {
    Album,
    AlbumEntry,
    CommitPhase,
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
    Maybe,
    PollCommitGQL,
    PollCommitQuery
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
    submitted: number;
    committed: number;
    albumTime: number;
    runningCommits: Set<string>;
    commitAfterUpload: boolean;
}


const uploadFileSuffix: Set<string> = new Set<string>(['jpg', 'nef', 'ts', 'mp4', 'mkv']);


const READ_PERMISSION: FileSystemHandlePermissionDescriptor = {mode: 'read'};
const DELETE_PERMISSION: FileSystemHandlePermissionDescriptor = {mode: 'readwrite'};

type ServerCommitState = {
    __typename?: 'CommitJob';
    currentPhase?: CommitPhase | null;
    currentStep?: number | null;
    totalStepCount?: number | null;
    album?: {
        __typename?: 'Album';
        id: string
    } | null
};

@Component({
    selector: 'app-import',
    templateUrl: './import.page.html',
    styleUrls: ['./import.page.scss'],
})
export class ImportPage implements OnInit, OnDestroy {

    public uploadOverallProgress = 0;
    public parentCandidates: string[] = [];
    public newAlbumParent = '';
    public newAlbumName = '';
    public newAlbumTimestamp = '';
    public currentFileName = '';
    public uploadFileProgress = 0;
    public importedFiles: ImportResult[] = [];
    public commitState: Map<string, ServerCommitState[]> = new Map<string, ServerCommitState[]>();

    public canImportFiles = window.showOpenFilePicker !== undefined;
    public uploadedStatisticsList: ImportStatisticsEntry[] = [];
    public pendingHiddenUploads = 0;
    public uploading = false;
    private identifiedAlbums: Map<string, string> = new Map<string, string>();
    private refreshCommitTimer: number;
    private running = true;

    constructor(private importListAlbumGQL: ImportListAlbumGQL,
                private importCreateAlbumGQL: ImportCreateAlbumGQL,
                private importSetAutoaddGQL: ImportSetAutoaddGQL,
                private importIdentifyFileGQL: ImportIdentifyFileGQL,
                private pollCommitGQL: PollCommitGQL,
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
        this.startRefreshCommit();
    }

    ngOnDestroy() {
        this.running = false;
        if (this.refreshCommitTimer !== undefined) {
            window.clearTimeout(this.refreshCommitTimer);
        }
    }

    private startRefreshCommit() {
        this.refreshCommitTimer = window.setTimeout(async () => {
            try {
                let polled = false;
                const commitState: Map<string, ServerCommitState[]> = new Map<string, ServerCommitState[]>();
                for (const entry of this.uploadedStatisticsList) {
                    const commitStates: ServerCommitState[] = [];
                    for (const id of entry.runningCommits) {
                        polled = true;
                        const result: PollCommitQuery = await this.serverApiService.query(this.pollCommitGQL, {id});
                        if (result.pollCommitState.currentPhase === CommitPhase.Done) {
                            const doneFiles = new Set<string>();
                            for (const file of result.pollCommitState.files) {
                                doneFiles.add(file.fileId);
                            }
                            const finishedFiles: UploadedFileEntry[] = [];
                            await this.storageService.processUploadedFiles(file => {
                                if (doneFiles.has(file.fileId)) {
                                    finishedFiles.push(file);
                                }
                            });
                            if (finishedFiles.length > 0) {
                                for (const finishedFile of finishedFiles) {
                                    finishedFile.commitEnqueued = undefined;
                                    finishedFile.committed = true;
                                }
                                await this.storageService.storeUploadedFileEntry(finishedFiles);
                                await this.updateUploadStats();
                            }
                        }
                        commitStates.push(result.pollCommitState);
                    }
                    commitState.set(entry.albumId, commitStates);
                }
                if (polled) {
                    await this.serverApiService.clear();
                }
                this.ngZone.run(() => {
                    this.commitState = commitState;
                });
            } finally {
                if (this.running) {
                    this.startRefreshCommit();
                }
            }
        }, 500);
    }

    async selectDirectory() {
        const directoryHandle: FileSystemDirectoryHandle = await showDirectoryPicker();

        const wait = await this.loadingController.create({message: 'Analysiere', duration: 120000});
        await wait.present();
        try {
            this.ngZone.run(() => this.uploading = true);
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
                const granted = await alreadyUploadedFile.sourceDirectory.queryPermission(READ_PERMISSION) === 'granted';
                if (granted) {
                    const file = await alreadyUploadedFile.sourceFile.getFile();
                    identifiedFiles.push(file);
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
                            committed: false,
                            commitEnqueued: undefined
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
            for (const albumData of this.uploadedStatisticsList) {
                if (albumData.commitAfterUpload) {
                    await this.commit(albumData.albumId);
                }
            }
        } finally {
            this.ngZone.run(() => this.uploading = false);
            await wait.dismiss();
        }
    }

    private async updateUploadStats() {
        const allUploadedFiles: UploadedFileEntry[] = [];
        await this.storageService.processUploadedFiles(async statEntry => {
            allUploadedFiles.push(statEntry);
        });
        const pendingIdentifies: Promise<[UploadedFileEntry, string]>[] = [];
        for (const uploadedFile of allUploadedFiles) {

            if (this.identifiedAlbums.has(uploadedFile.fileId)) {
                const albumId = this.identifiedAlbums.get(uploadedFile.fileId);
                pendingIdentifies.push(Promise.resolve([uploadedFile, albumId]));
            } else {
                const currentState = await uploadedFile.sourceDirectory.queryPermission(READ_PERMISSION);
                if (currentState !== 'granted') {
                    continue;
                }
                try {
                    const file = await uploadedFile.sourceFile.getFile();
                    pendingIdentifies.push(this.serverApiService.query<ImportIdentifyFileQuery, ImportIdentifyFileQueryVariables>(
                        this.importIdentifyFileGQL, {
                            file: {
                                fileId: uploadedFile.fileId,
                                size: uploadedFile.size,
                                filename: file.name
                            }
                        }).then(async preview => {
                        const identifiedAlbumId = preview?.previewImport.id;
                        if (!identifiedAlbumId) {
                            const errorMsg = await this.toastController.create({
                                message: 'Error identify file ' + file.name,
                                color: 'warning',
                                duration: 10000
                            });
                            await errorMsg.present();
                        }
                        this.identifiedAlbums.set(uploadedFile.fileId, identifiedAlbumId);
                        return [uploadedFile, identifiedAlbumId];
                    }));
                } catch (e) {
                    console.log('Error on file', uploadedFile.sourceFile.name, e);
                }
            }
        }
        const autocommitAlbum = new Set<string>();
        for (const importStatisticsEntry of this.uploadedStatisticsList) {
            if (importStatisticsEntry.commitAfterUpload) {
                autocommitAlbum.add(importStatisticsEntry.albumId);
            }
        }
        const statPerAlbum: Map<string, ImportStatisticsEntry> = new Map<string, ImportStatisticsEntry>();
        for (const [uploadedFile, albumId] of await Promise.all(pendingIdentifies)) {
            if (albumId) {
                let statOfAlbum: ImportStatisticsEntry;
                if (statPerAlbum.has(albumId)) {
                    statOfAlbum = statPerAlbum.get(albumId);
                } else {
                    const albumData = await this.dataService.getAlbum(albumId);
                    statOfAlbum = {
                        albumId,
                        uploaded: 0,
                        committed: 0,
                        submitted: 0,
                        albumName: albumData.title,
                        albumTime: albumData.albumTime,
                        runningCommits: new Set<string>(),
                        commitAfterUpload: autocommitAlbum.has(albumId)
                    };
                    statPerAlbum.set(albumId, statOfAlbum);
                }

                if (uploadedFile.committed) {
                    statOfAlbum.committed += 1;
                } else {
                    const commitId = uploadedFile.commitEnqueued;
                    if (commitId !== undefined) {
                        statOfAlbum.runningCommits.add(commitId);
                        statOfAlbum.submitted += 1;
                    } else {
                        statOfAlbum.uploaded += 1;
                    }
                }
            }

        }


        this.ngZone.run(() => {
            this.uploadedStatisticsList = this.uploadedStatisticsList.flatMap(importStatisticsEntry => {
                const updatedStats = statPerAlbum.get(importStatisticsEntry.albumId);
                if (updatedStats) {
                    statPerAlbum.delete(importStatisticsEntry.albumId);
                    importStatisticsEntry.runningCommits = updatedStats.runningCommits;
                    importStatisticsEntry.uploaded = updatedStats.uploaded;
                    importStatisticsEntry.submitted = updatedStats.submitted;
                    importStatisticsEntry.committed = updatedStats.committed;
                    return [updatedStats];
                } else {
                    return [];
                }
            });
            for (const remainingStat of statPerAlbum.values()) {
                this.uploadedStatisticsList.push(remainingStat);
            }
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

    async commit(albumId: string): Promise<boolean> {
        try {
            let takenSize = 0;
            const filesOfAlbum: UploadedFileEntry[] = [];
            await this.storageService.processUploadedFiles(uploadedFile => {
                if (this.identifiedAlbums.get(uploadedFile.fileId) === albumId &&
                    !uploadedFile.committed &&
                    uploadedFile.commitEnqueued === undefined) {
                    filesOfAlbum.push(uploadedFile);
                }
            });
            const files: ImportFile[] = [];
            for (const uploadedFile of filesOfAlbum) {
                const file = await uploadedFile.sourceFile.getFile();
                const nextSize = takenSize + file.size;
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
                    this.importCommitGQL, {data: {albumId, files}}
                );
                const submittedFileIds: Set<string> = new Set<string>();
                for (const resultEntry of commitResult.enqueueCommit.files) {
                    submittedFileIds.add(resultEntry.fileId);
                }
                const commitJobId = commitResult.enqueueCommit.commitJobId;
                const committedFiles: UploadedFileEntry[] = [];
                for (const uploadedFileEntry of filesOfAlbum) {
                    if (submittedFileIds.has(uploadedFileEntry.fileId)) {
                        uploadedFileEntry.commitEnqueued = commitJobId;
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
        }
    }

    async deleteCommitted(albumId: string) {
        const committedFiles: UploadedFileEntry[] = [];
        await this.storageService.processUploadedFiles(async statEntry => {
            if (albumId === this.identifiedAlbums.get(statEntry.fileId) && statEntry.committed === true) {
                committedFiles.push(statEntry);
            }
        });


        try {
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
        }
    }


    public openNavigationMenu(): Promise<void> {
        return this.menuController.open('navigation').then();
    }


    public async cleanAllPendingUploads() {
        await this.storageService.removeAllUploadedFiles();
        await this.updateUploadStats();
    }
}
