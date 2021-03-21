import {Component, OnInit} from '@angular/core';
import {
    Album,
    AlbumEntry,
    ImportCommitGQL,
    ImportCreateAlbumGQL,
    ImportedFile,
    ImportFile,
    ImportListAlbumGQL,
    ImportSetAutoaddGQL,
    Maybe
} from '../generated/graphql';
import {ServerApiService} from '../service/server-api.service';
import {ToastController} from '@ionic/angular';
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

interface ImportStatisticsEntry {
    albumName: string;
    entryCount: number;
}


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
    public importedStatistics: ImportStatisticsEntry[] = [];

    constructor(private importListAlbumGQL: ImportListAlbumGQL,
                private importCreateAlbumGQL: ImportCreateAlbumGQL,
                private importSetAutoaddGQL: ImportSetAutoaddGQL,
                private importCommitGQL: ImportCommitGQL,
                private serverApiService: ServerApiService,
                private httpClient: HttpClient,
                private toastController: ToastController) {
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
        const dcimDirectory = await directoryHandle.getDirectoryHandle('DCIM');
        const filesToUpload: [FileSystemFileHandle, FileSystemDirectoryHandle][] = [];
        for await (const imageFolderDirectory of dcimDirectory.values()) {
            if (imageFolderDirectory instanceof FileSystemDirectoryHandle) {
                const dir: FileSystemDirectoryHandle = await dcimDirectory.getDirectoryHandle(imageFolderDirectory.name);
                for await (const fileEntry of dir.entries()) {
                    const [name, file] = fileEntry;
                    if (file instanceof FileSystemFileHandle) {
                        filesToUpload.push([file, dir]);
                    }
                }
            }
        }
        let totalSize = 0;
        for (const item of filesToUpload) {
            totalSize += (await item[0].getFile()).size;
        }
        const uploadCount = filesToUpload.length;
        let uploadedSize = 0;
        const pendingUploadedFiles = new Map<string, [FileSystemFileHandle, FileSystemDirectoryHandle]>();
        const commit: () => Promise<void> = async () => {
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
            this.updateImportStats();
        };
        let lastCommitTime = Date.now();
        for (const item of filesToUpload) {
            const data = await item[0].getFile();
            this.currentFileName = item[0].name;
            const result: UploadResult = await new Promise<UploadResult>((resolve, reject) => {
                this.httpClient.post('/rest/import', data, {reportProgress: true, observe: 'events'})
                    .subscribe((event: HttpEvent<UploadResult>) => {
                        switch (event.type) {
                            case HttpEventType.Response:
                                resolve(event.body);
                                break;
                            case HttpEventType.UploadProgress:
                                this.uploadFileProgress = event.loaded / event.total;
                                this.uploadOverallProgress = (event.loaded + uploadedSize) / totalSize;
                                break;
                        }
                    }, error => reject(error));
            });
            if (data.size === result.byteCount) {
                pendingUploadedFiles.set(result.fileId, item);
            }
            uploadedSize += data.size;
            this.uploadOverallProgress = uploadedSize / totalSize;
            if (lastCommitTime + 10 * 1000 < Date.now()) {
                await commit();
                lastCommitTime = Date.now();
            }
        }
        await commit();
        this.currentFileName = '';
        console.log(uploadCount + ' photos gefunden');
        this.uploadOverallProgress = 0;
        this.uploadFileProgress = 0;
    }

    private updateImportStats() {
        const fileCounts = new Map<string, number>();
        for (const importedFileData of this.importedFiles) {
            const album = importedFileData.createdEntry.albumEntry.album;
            const oldCount = fileCounts.get(album.name) || 0;
            fileCounts.set(album.name, oldCount + 1);

        }
        this.importedStatistics = [];
        fileCounts.forEach((count, albumName) => {
            this.importedStatistics.push({albumName, entryCount: count});
        });
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
        const removedFiles = new Set<string>();
        const removedPromises: Promise<void>[] = [];
        for (const fileEntry of this.importedFiles) {
            removedPromises.push(fileEntry.directory.removeEntry(fileEntry.file.name));
            removedFiles.add(fileEntry.createdEntry.fileId);
        }
        await Promise.all(removedPromises);
        this.importedFiles = this.importedFiles.filter(entry => !removedFiles.has(entry.createdEntry.fileId));
        this.updateImportStats();
    }
}
