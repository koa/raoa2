import {Component, NgZone, OnInit} from '@angular/core';
import {
    Album,
    AlbumEntry,
    ImportCommitGQL,
    ImportCreateAlbumGQL,
    ImportedFile,
    ImportFile,
    ImportIdentifyFileGQL,
    ImportListAlbumGQL,
    ImportSetAutoaddGQL,
    Maybe
} from '../generated/graphql';
import {ServerApiService} from '../service/server-api.service';
import {LoadingController, ToastController} from '@ionic/angular';
import {HttpClient, HttpEvent, HttpEventType} from '@angular/common/http';

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

interface ImportUploadedFileEntry {
    fileId: string;
    albumId: string;
    sourceFile: FileSystemFileHandle;
    sourceDirectory: FileSystemDirectoryHandle;
}

interface ImportStatisticsEntry {
    albumName: string;
    uploaded: ImportUploadedFileEntry[];
    committed: ImportUploadedFileEntry[];
}


const uploadFileSuffix: Set<string> = new Set<string>(['jpg', 'nef', 'ts', 'mp4', 'mkv']);


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
    public canImportFiles = window.showOpenFilePicker !== undefined;

    constructor(private importListAlbumGQL: ImportListAlbumGQL,
                private importCreateAlbumGQL: ImportCreateAlbumGQL,
                private importSetAutoaddGQL: ImportSetAutoaddGQL,
                private importIdentifyFileGQL: ImportIdentifyFileGQL,
                private importCommitGQL: ImportCommitGQL,
                private serverApiService: ServerApiService,
                private httpClient: HttpClient,
                private toastController: ToastController,
                private loadingController: LoadingController,
                private ngZone: NgZone) {
    }

    async ngOnInit() {
        const albumData = await this.serverApiService.query(this.importListAlbumGQL, {});
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
    }

    async selectDirectory() {
        const directoryHandle: FileSystemDirectoryHandle = await showDirectoryPicker();
        const filesToUpload: [FileSystemFileHandle, FileSystemDirectoryHandle][] = [];
        await this.processDirectory(directoryHandle, filesToUpload);
        let totalSize = 0;
        for (const item of filesToUpload) {
            totalSize += (await item[0].getFile()).size;
        }
        let uploadedSize = 0;
        const pendingUploadedFiles = new Map<string, ImportStatisticsEntry>();
        const commit: (album: string) => Promise<void> = async () => {
            const files: ImportFile[] = [];
            for (const key of pendingUploadedFiles.keys()) {
                const handle = pendingUploadedFiles.get(key);
                const file = await handle[0].getFile();
                files.push({fileId: key, filename: handle[0].name, size: file.size});
            }
            const commitResult = await this.serverApiService.update(this.importCommitGQL, {files});
            for (const resultEntry of commitResult.commitImport) {
                const fileId = resultEntry.fileId;
                const uploadedFileHandle = pendingUploadedFiles.get(fileId);
                this.importedFiles.push({
                    directory: uploadedFileHandle[1],
                    file: uploadedFileHandle[0],
                    createdEntry: resultEntry
                });
                pendingUploadedFiles.delete(fileId);
            }
        };
        const lastCommitTime = Date.now();
        for (const item of filesToUpload) {
            const data = await item[0].getFile();
            this.ngZone.run(() => this.currentFileName = item[0].name);
            const result: UploadResult = await new Promise<UploadResult>((resolve, reject) => {
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
                uploadedSize += data.size;
                this.ngZone.run(() => this.uploadOverallProgress = uploadedSize / totalSize);
                const previewImport = (await this.serverApiService.query(this.importIdentifyFileGQL, {
                    file: {
                        fileId: result.fileId,
                        size: data.size,
                        filename: item[0].name
                    }
                }))?.previewImport;
                const albumId = previewImport?.id;
                if (albumId) {
                    this.ngZone.run(() => {
                        let albumData: ImportStatisticsEntry;
                        if (this.uploadedStatistics.has(albumId)) {
                            albumData = this.uploadedStatistics.get(albumId);
                        } else {
                            albumData = {
                                albumName: previewImport.name,
                                uploaded: [],
                                committed: []

                            };
                            this.uploadedStatistics.set(albumId, albumData);
                        }
                        albumData.uploaded.push({
                            albumId,
                            fileId: result.fileId,
                            sourceDirectory: item[1],
                            sourceFile: item[0]
                        });
                    });
                } else {
                    await this.toastController.create({message: 'Error identify file ' + item[0].name, color: 'warning', duration: 10000});
                }
            }

        }
        this.ngZone.run(() => {
            this.currentFileName = '';
            this.uploadOverallProgress = 0;
            this.uploadFileProgress = 0;
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
        console.log(this.newAlbumParent);
        const path: string[] = [];
        this.appendPath(this.newAlbumParent, path);
        this.appendPath(this.newAlbumName, path);
        if (path.length === 0) {
            return;
        }
        const result = await this.serverApiService.update(this.importCreateAlbumGQL, {path});
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

    async commit(statEntry: ImportStatisticsEntry) {
        const files: ImportFile[] = [];

        for (const uploadedFile of statEntry.uploaded) {
            const file = await uploadedFile.sourceFile.getFile();
            files.push({fileId: uploadedFile.fileId, filename: uploadedFile.sourceFile.name, size: file.size});
        }
        const wait = await this.loadingController.create({message: 'Commit', duration: 120000});
        await wait.present();
        const commitResult = await this.serverApiService.update(this.importCommitGQL, {files});
        await wait.dismiss();
        this.ngZone.run(() => {
            for (const resultEntry of commitResult.commitImport) {
                const fileId = resultEntry.fileId;
                for (let index = 0; index < statEntry.uploaded.length; index++) {
                    const entry = statEntry.uploaded[index];
                    if (entry.fileId === fileId) {
                        statEntry.committed.push(entry);
                        statEntry.uploaded.splice(index, 1);
                        break;
                    }
                }
            }
        });
    }

    async deleteCommited(statEntry: ImportStatisticsEntry) {
        const commitedFiles = statEntry.committed;
        const wait = await this.loadingController.create({message: 'Delete', duration: 120000});
        await wait.present();
        while (commitedFiles.length > 0) {
            const fileEntry = this.ngZone.run(() => commitedFiles.pop());
            await fileEntry.sourceDirectory.removeEntry(fileEntry.sourceFile.name);
        }
        await wait.dismiss();
    }
}
