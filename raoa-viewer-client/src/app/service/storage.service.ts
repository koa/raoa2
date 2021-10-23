import {Injectable} from '@angular/core';
import Dexie, {Table, WhereClause} from 'dexie';
import Semaphore from 'semaphore-async-await';

export interface AlbumData {
    id: string;
    title: string;
    albumVersion: string;
    albumEntryVersion: string;
    lastUpdated: number;
    fnchAlbumId: string;
    entryCount: number;
    albumTime: number;
    syncOffline: number;
    offlineSyncedVersion: string;
}

export interface AlbumEntryData {
    albumId: string;
    albumEntryId: string;
    name: string;
    targetWidth: number;
    targetHeight: number;
    created: number;
    keywords: string[];
    contentType: string;
    entryType: 'image' | 'video';
    cameraModel: string;
    exposureTime: number;
    fNumber: number;
    focalLength35: number;
    isoSpeedRatings: number;
}

export interface ImageBlob {
    albumId: string;
    albumEntryId: string;
    mediaSize: number;
    data: Blob;
}

interface PendingKeywordAddEntry {
    albumId: string;
    albumEntryId: string;
    keyword: string;
}

interface PendingKeywordRemoveEntry {
    albumId: string;
    albumEntryId: string;
    keyword: string;
}

export interface KeywordState {
    existingKeywords: Set<string>;
    pendingAddKeywords: Set<string>;
    pendingRemoveKeywords: Set<string>;
}

export interface UserPermissions {
    canManageUsers: boolean;
    canEdit: boolean;
}

export const MAX_SMALL_IMAGE_SIZE = 800;

@Injectable({
    providedIn: 'root'
})
export class StorageService extends Dexie {
    private readonly userPermissionsTable: Table<UserPermissions>;


    constructor() {
        super('RaoaDatabase');
        this.version(5)
            .stores({
                albumData: 'id',
                albumEntryData: '[albumId+albumEntryId], albumId, keywords, entryType, created',
                pendingKeywordAddData: '[albumId+albumEntryId+keyword], [albumId+albumEntryId], albumId',
                pendingKeywordRemoveData: '[albumId+albumEntryId+keyword], [albumId+albumEntryId], albumId',
                bigImages: '[albumId+albumEntryId], albumId',
                smallImages: '[albumId+albumEntryId], albumId',
                userPermissions: '++id'
            });
        this.albumDataTable = this.table('albumData');
        this.albumEntryDataTable = this.table('albumEntryData');
        this.pendingKeywordAddDataTable = this.table('pendingKeywordAddData');
        this.pendingKeywordRemoveDataTable = this.table('pendingKeywordRemoveData');
        this.smallImagesTable = this.table('smallImages');
        this.bigImagesTable = this.table('bigImages');
        this.userPermissionsTable = this.table('userPermissions');
        this.whereAddPendingKeyword = this.pendingKeywordAddDataTable.where(['albumId', 'albumEntryId']);
        this.whereRemovePendingKeyword = this.pendingKeywordRemoveDataTable.where(['albumId', 'albumEntryId']);
        this.albumEntryByEntryAndAlbum = this.albumEntryDataTable.where(['albumId', 'albumEntryId']);
    }

    private readonly albumDataTable: Table<AlbumData, string>;
    private readonly albumEntryDataTable: Table<AlbumEntryData, [string, string]>;
    private readonly pendingKeywordAddDataTable: Table<PendingKeywordAddEntry, [string, string, string]>;
    private readonly pendingKeywordRemoveDataTable: Table<PendingKeywordRemoveEntry, [string, string, string]>;
    private readonly smallImagesTable: Table<ImageBlob, [string, string, number]>;
    private readonly bigImagesTable: Table<ImageBlob, [string, string, number]>;
    private readonly whereAddPendingKeyword: WhereClause<PendingKeywordAddEntry, [string, string, string]>;
    private readonly whereRemovePendingKeyword: WhereClause<PendingKeywordRemoveEntry, [string, string, string]>;
    private readonly albumEntryByEntryAndAlbum: WhereClause<AlbumEntryData, [string, string]>;
    private readonly lastAccessTime = new Map<string, number>();
    private readonly cleanupSemaphore = new Semaphore(1);

    public async updateAlbumEntries(entries: AlbumEntryData[], album?: AlbumData) {
        await this.transaction('rw',
            this.albumDataTable,
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                if (album) {
                    const keepEntries = new Set<string>();
                    entries.forEach(entry => keepEntries.add(entry.albumEntryId));
                    await this.albumDataTable.put(album);
                    await this.albumEntryDataTable
                        .where('albumId')
                        .equals(album.id)
                        .filter(entry => !keepEntries.has(entry.albumEntryId))
                        .delete();
                }
                await this.albumEntryDataTable.bulkPut(entries);
                for (const entry of entries) {
                    await this.adjustPendingKeywords(entry);
                }
            });
    }

    public async listAlbums(): Promise<AlbumData[]> {
        const ret: AlbumData[] = [];
        await this.transaction('rw', this.albumDataTable, async () => this.albumDataTable.each(e => ret.push(e)));
        return ret;
    }


    public async updateAlbums(albums: AlbumData[]) {
        await this.transaction('rw', this.albumDataTable, async () => {
            const dataBefore = new Map<string, AlbumData>();
            await this.albumDataTable.each(oldAlbumEntry => dataBefore.set(oldAlbumEntry.id, oldAlbumEntry));
            const modifiedAlbums: AlbumData[] = [];
            for (const newAlbumEntry of albums) {
                const albumEntryBefore = dataBefore.get(newAlbumEntry.id);
                if (albumEntryBefore === undefined) {
                    newAlbumEntry.albumEntryVersion = undefined;
                    modifiedAlbums.push(newAlbumEntry);
                } else {
                    if (albumEntryBefore.albumVersion !== newAlbumEntry.albumVersion) {
                        modifiedAlbums.push({
                            albumEntryVersion: albumEntryBefore.albumEntryVersion,
                            albumTime: newAlbumEntry.albumTime,
                            albumVersion: newAlbumEntry.albumVersion,
                            entryCount: newAlbumEntry.entryCount,
                            fnchAlbumId: newAlbumEntry.fnchAlbumId,
                            id: newAlbumEntry.id,
                            lastUpdated: Date.now(),
                            title: newAlbumEntry.title,
                            syncOffline: albumEntryBefore.syncOffline,
                            offlineSyncedVersion: albumEntryBefore.offlineSyncedVersion
                        });
                    }
                    dataBefore.delete(newAlbumEntry.id);
                }
            }
            this.albumDataTable.bulkPut(modifiedAlbums);
            const remainingKeys: string[] = [];
            dataBefore.forEach((data, key) => remainingKeys.push(key));
            this.albumDataTable.bulkDelete(remainingKeys);
        });
    }

    private async adjustPendingKeywords(entry: AlbumEntryData) {
        const keywords = new Set<string>(entry.keywords);
        await this.whereAddPendingKeyword
            .equals([entry.albumId, entry.albumEntryId])
            .each(addEntry => {
                if (keywords.has(addEntry.keyword)) {
                    return this.pendingKeywordAddDataTable.delete([entry.albumId, entry.albumEntryId, addEntry.keyword]);
                }
            });
        await this.whereRemovePendingKeyword
            .equals([entry.albumId, entry.albumEntryId])
            .each(removeEntry => {
                if (!keywords.has(removeEntry.keyword)) {
                    return this.pendingKeywordRemoveDataTable.delete([entry.albumId, entry.albumEntryId, removeEntry.keyword]);
                }
            });
    }

    public async listAlbum(albumId: string, filter?: (entry: AlbumEntryData) => boolean):
        Promise<[AlbumData | undefined, AlbumEntryData[] | undefined]> {
        this.lastAccessTime.set(albumId, Date.now());
        return this.transaction('r',
            [this.albumDataTable, this.albumEntryDataTable, this.pendingKeywordAddDataTable, this.pendingKeywordRemoveDataTable],
            async (tx) => {
                try {
                    const foundAlbumData = await this.albumDataTable.get(albumId);
                    if (foundAlbumData === undefined) {
                        return [undefined, undefined];
                    }
                    if (foundAlbumData.albumEntryVersion === undefined
                        || foundAlbumData.albumEntryVersion !== foundAlbumData.albumVersion) {
                        return [foundAlbumData, undefined];
                    }
                    const entryFilter = filter ? filter : e => true;
                    const addKeywords = new Map<string, Set<string>>();
                    const removeKeywords = new Map<string, Set<string>>();
                    await Promise.all([
                        this.pendingKeywordAddDataTable.where('albumId').equals(albumId).each(entry => {
                            const albumEntryId = entry.albumEntryId;
                            const keyword = entry.keyword;
                            if (addKeywords.has(albumEntryId)) {
                                addKeywords.get(albumEntryId).add(keyword);
                            } else {
                                addKeywords.set(albumEntryId, new Set<string>([keyword]));
                            }
                        }),
                        this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).each(entry => {
                            const albumEntryId = entry.albumEntryId;
                            const keyword = entry.keyword;
                            if (removeKeywords.has(albumEntryId)) {
                                removeKeywords.get(albumEntryId).add(keyword);
                            } else {
                                removeKeywords.set(albumEntryId, new Set<string>([keyword]));
                            }
                        })]);
                    const finalList: AlbumEntryData[] = [];
                    (await this.albumEntryDataTable.where('albumId').equals(albumId).filter(entryFilter).sortBy('created'))
                        .forEach(entry => {
                            const keywords = new Set<string>(entry.keywords);
                            if (addKeywords.has(entry.albumEntryId)) {
                                addKeywords.get(entry.albumEntryId).forEach(kw => keywords.add(kw));
                            }
                            if (removeKeywords.has(entry.albumEntryId)) {
                                removeKeywords.get(entry.albumEntryId).forEach(kw => keywords.delete(kw));
                            }
                            const kwList: string[] = [];
                            keywords.forEach(kw => kwList.push(kw));
                            finalList.push({
                                cameraModel: entry.cameraModel,
                                exposureTime: entry.exposureTime,
                                fNumber: entry.fNumber,
                                focalLength35: entry.focalLength35,
                                isoSpeedRatings: entry.isoSpeedRatings,
                                albumEntryId: entry.albumEntryId,
                                albumId: entry.albumId,
                                created: entry.created,
                                entryType: entry.entryType,
                                keywords: kwList,
                                name: entry.name,
                                targetHeight: entry.targetHeight,
                                targetWidth: entry.targetWidth,
                                contentType: entry.contentType
                            });
                        });
                    return [foundAlbumData, finalList];
                } catch (e) {
                    console.log('error');
                    console.log(e);
                    throw e;
                }
            });
    }

    public async addKeyword(albumId: string, albumEntryIds: string[], keywords: string[]): Promise<void> {
        await this.transaction('rw',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                for (const albumEntryId of albumEntryIds) {
                    for (const keyword of keywords) {
                        await Promise.all(
                            [
                                this.pendingKeywordRemoveDataTable.delete([albumId, albumEntryId, keyword]).then(v => undefined),
                                this.albumEntryDataTable.get([albumId, albumEntryId])
                                    .then((storedEntry: AlbumEntryData | undefined) => {
                                        if (storedEntry !== undefined) {
                                            if (storedEntry.keywords !== undefined
                                                && storedEntry.keywords.find(k => k === keyword)) {
                                                return Promise.resolve();
                                            }
                                            return this.pendingKeywordAddDataTable.put({
                                                albumId,
                                                albumEntryId,
                                                keyword
                                            }).then(v => undefined);
                                        }
                                    }).then(v => undefined)]);
                    }
                }
            });
    }

    public async removeKeyword(albumId: string, albumEntryIds: string[], keywords: string[]): Promise<void> {
        await this.transaction('rw',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                for (const keyword of keywords) {
                    for (const albumEntryId of albumEntryIds) {
                        await Promise.all(
                            [
                                this.pendingKeywordAddDataTable.delete([albumId, albumEntryId, keyword]).then(v => undefined),
                                this.albumEntryDataTable.get([albumId, albumEntryId])
                                    .then((storedEntry: AlbumEntryData | undefined) => {
                                        if (storedEntry !== undefined
                                            && storedEntry.keywords !== undefined
                                            && storedEntry.keywords.find(k => k === keyword)) {
                                            return this.pendingKeywordRemoveDataTable.put({
                                                albumEntryId,
                                                albumId,
                                                keyword
                                            }).then(v => undefined);
                                        }
                                        return Promise.resolve();
                                    }).then(v => undefined)]);
                    }
                }
            });
    }

    public async currentKeywordStates(albumId: string, entries: Set<string>): Promise<Map<string, KeywordState>> {
        return this.transaction('r',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                const entryKeys: [string, string][] = [];
                entries.forEach(albumEntryId => entryKeys.push([albumId, albumEntryId]));
                const ret = new Map<string, KeywordState>();
                const entryFunction = entryId => {
                    const existingEntry = ret.get(entryId);
                    if (existingEntry !== undefined) {
                        return existingEntry;
                    }
                    const newEntry = {
                        existingKeywords: new Set<string>(),
                        pendingAddKeywords: new Set<string>(),
                        pendingRemoveKeywords: new Set<string>()
                    };
                    ret.set(entryId, newEntry);
                    return newEntry;
                };
                await Promise.all([
                    this.albumEntryByEntryAndAlbum
                        .anyOf(entryKeys)
                        .each(entry => {
                            const keywords = entryFunction(entry.albumEntryId).existingKeywords;
                            entry.keywords.forEach(kw => keywords.add(kw));
                        }),
                    this.pendingKeywordAddDataTable
                        .where(['albumId', 'albumEntryId'])
                        .anyOf(entryKeys)
                        .each(addEntry => entryFunction(addEntry.albumEntryId).pendingAddKeywords.add(addEntry.keyword)),
                    this.pendingKeywordRemoveDataTable
                        .where(['albumId', 'albumEntryId'])
                        .anyOf(entryKeys)
                        .each(removeEntry => entryFunction(removeEntry.albumEntryId).pendingRemoveKeywords.add(removeEntry.keyword))
                ]);
                return ret;
            });
    }

    public async pendingAddKeywords(albumId: string, addEntryCallback: (PendingKeywordAddEntry) => void) {
        await this.transaction('r', this.pendingKeywordAddDataTable, async () => {
            this.pendingKeywordAddDataTable.where('albumId').equals(albumId).each(addEntryCallback);
        });
    }

    public async pendingRemoveKeywords(albumId: string, removeEntryCallback: (PendingKeywordRemoveEntry) => void) {
        await this.transaction('r', this.pendingKeywordRemoveDataTable, async () => {
            this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).each(removeEntryCallback);
        });
    }

    public hasPendingMutations(albumId: string): Promise<boolean> {
        return this.transaction('r', this.pendingKeywordAddDataTable, this.pendingKeywordRemoveDataTable, async () => {
            const [addCount, removeCount] = await Promise.all([
                this.pendingKeywordAddDataTable.where('albumId').equals(albumId).count(),
                this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).count()
            ]);
            return addCount + removeCount > 0;
        });
    }

    public clearPendingMutations(albumId: string): Promise<void> {
        return this.transaction('rw', this.pendingKeywordAddDataTable, this.pendingKeywordRemoveDataTable, async () => {
            await Promise.all([
                this.pendingKeywordAddDataTable.where('albumId').equals(albumId).delete(),
                this.pendingKeywordRemoveDataTable.where('albumId').equals(albumId).delete()
            ]);
        });
    }

    public getAlbumEntry(albumId: string, albumEntryId: string): Promise<[AlbumEntryData, Set<string>] | undefined> {
        this.lastAccessTime.set(albumId, Date.now());
        return this.transaction('r',
            this.albumEntryDataTable,
            this.pendingKeywordAddDataTable,
            this.pendingKeywordRemoveDataTable,
            async () => {
                const key = [albumId, albumEntryId];
                const [entry, addKeywords, removeKeywords] = await Promise.all([
                    this.albumEntryDataTable.get(key),
                    this.whereAddPendingKeyword.equals(key).toArray(),
                    this.whereRemovePendingKeyword.equals(key).toArray()]);
                if (entry === undefined) {
                    return undefined;
                }
                const keywords = new Set(entry.keywords);
                addKeywords.forEach(e => keywords.add(e.keyword));
                removeKeywords.forEach(e => keywords.delete(e.keyword));
                return [entry, keywords];
            }
        );
    }

    public readImage(albumId: string, albumEntryId: string, minSize: number, online: boolean): Promise<ImageBlob | undefined> {
        return this.transaction('r', this.bigImagesTable, this.smallImagesTable, async () => {
            if (online) {
                if (minSize <= MAX_SMALL_IMAGE_SIZE) {
                    const smallStoredData = await this.smallImagesTable.get([albumId, albumEntryId]);
                    if (smallStoredData !== undefined && smallStoredData.mediaSize >= minSize) {
                        return smallStoredData;
                    }
                    return undefined;
                }
                const bigStoredData = await this.bigImagesTable.get([albumId, albumEntryId]);
                if (bigStoredData !== undefined && bigStoredData.mediaSize >= minSize) {
                    return bigStoredData;
                }
                return undefined;
            } else {
                if (minSize <= MAX_SMALL_IMAGE_SIZE) {
                    const smallStoredData = await this.smallImagesTable.get([albumId, albumEntryId]);
                    if (smallStoredData !== undefined && smallStoredData.mediaSize >= minSize) {
                        return smallStoredData;
                    }
                    const bigStoredData = await this.bigImagesTable.get([albumId, albumEntryId]);
                    if (bigStoredData !== undefined) {
                        return bigStoredData;
                    }
                    return smallStoredData;
                } else {
                    const bigStoredData = await this.bigImagesTable.get([albumId, albumEntryId]);
                    if (bigStoredData !== undefined) {
                        return bigStoredData;
                    }
                    return this.smallImagesTable.get([albumId, albumEntryId]);
                }
            }
        });
    }

    public findDeprecatedAlbums(): Promise<string[]> {
        return this.transaction('r', this.albumDataTable, async () => {
            const ret: string[] = [];
            this.albumDataTable
                .filter(album => album.syncOffline > 0 && album.albumEntryVersion !== album.albumVersion)
                .each(album => ret.push(album.id));
            return ret;
        });
    }

    public findMissingImagesOfAlbum(albumId: string, minSize: number): Promise<[Set<string>, Set<string>]> {
        return this.transaction('r', this.albumEntryDataTable, this.bigImagesTable, this.smallImagesTable, async () => {
            const smallEntries = new Set<string>();
            const bigEntries = new Set<string>();
            await this.albumEntryDataTable.where('albumId').equals(albumId).each(entry => {
                bigEntries.add(entry.albumEntryId);
                smallEntries.add(entry.albumEntryId);
            });
            await this.smallImagesTable
                .where('albumId').equals(albumId).and(img => img.mediaSize >= MAX_SMALL_IMAGE_SIZE)
                .each(img => smallEntries.delete(img.albumEntryId));
            await this.bigImagesTable
                .where('albumId').equals(albumId).and(img => img.mediaSize >= minSize)
                .each(img => bigEntries.delete(img.albumEntryId));
            return [smallEntries, bigEntries];
        });
    }

    public async storeImages(data: ImageBlob[]): Promise<void> {
        await this.cleanupSemaphore.acquire();
        try {
            if (navigator.storage && navigator.storage.estimate) {
                const estimation = await navigator.storage.estimate();
                const quota = estimation.quota;
                const usage = estimation.usage;
                let totalSize = 0;
                data.forEach(img => totalSize += img.data.size);
                if (usage + totalSize * 1.5 > quota - 10 * 1000 * 1000) {
                    // console.log('storage limit reached');
                    // console.log(`Quota: ${quota}`);
                    // console.log(`Usage: ${usage}`);
                    await this.cleanupOldestAlbum();
                }
            }
        } finally {
            this.cleanupSemaphore.release();
        }
        return this.transaction('rw', this.smallImagesTable, this.bigImagesTable, async () => {
            for (const image of data) {
                if (image.mediaSize <= MAX_SMALL_IMAGE_SIZE) {
                    const oldEntry = await this.smallImagesTable.get([image.albumId, image.albumEntryId]);
                    if (oldEntry === undefined || oldEntry.mediaSize < image.mediaSize) {
                        await this.smallImagesTable.put(image);
                    }
                } else {
                    const oldEntry = await this.bigImagesTable.get([image.albumId, image.albumEntryId]);
                    if (oldEntry === undefined || oldEntry.mediaSize < image.mediaSize) {
                        await this.bigImagesTable.put(image);
                    }

                }
            }
        });
    }

    public storeImage(data: ImageBlob): Promise<void> {
        return this.storeImages([data]);
    }


    private async cleanupOldestAlbum(): Promise<void> {
        return this.transaction('rw',
            this.albumDataTable,
            this.albumEntryDataTable,
            this.smallImagesTable,
            this.bigImagesTable, async () => {
                const albumsWithData: string[] = [];
                await this.albumDataTable.each(a => {
                    if (a.albumEntryVersion !== undefined) {
                        albumsWithData.push(a.id);
                    }
                });
                // cleanup orphaned entries
                const deleteOrphanCount = await this.albumEntryDataTable.where('albumId').noneOf(albumsWithData).delete() +
                    await this.bigImagesTable.where('albumId').noneOf(albumsWithData).delete() +
                    await this.smallImagesTable.where('albumId').noneOf(albumsWithData).delete();
                if (deleteOrphanCount > 0) {
                    return;
                }
                let oldestEntryTime = Number.MAX_SAFE_INTEGER;
                let oldestEntryKey;
                albumsWithData.forEach(key => {
                    const time = this.lastAccessTime.get(key) || -1;
                    if (time < oldestEntryTime) {
                        oldestEntryKey = key;
                        oldestEntryTime = time;
                    }
                });
                const nextFoundKey = oldestEntryKey;
                if (nextFoundKey === undefined) {
                    return;
                }
                const album = await this.albumDataTable.get(nextFoundKey);
                // remove images
                if (album !== undefined) {
                    album.syncOffline = 0;
                    album.offlineSyncedVersion = undefined;
                    await this.albumDataTable.put(album);
                }
                const removedImages = await this.bigImagesTable.where('albumId').equals(nextFoundKey).delete() +
                    await this.bigImagesTable.where('albumId').equals(nextFoundKey).delete()
                ;
                if (removedImages > 0) {
                    return;
                }
                // remove entries
                if (album !== undefined) {
                    album.albumEntryVersion = undefined;
                    await this.albumDataTable.put(album);
                }
                await this.albumEntryDataTable.where('albumId').equals(nextFoundKey).delete();
            }).catch(ex => {
            console.error('cannot cleanup data', ex);
        });
    }

    public getUserPermissions(): Promise<UserPermissions | undefined> {
        return this.transaction('r',
            this.userPermissionsTable, async () => {
                const entries = await this.userPermissionsTable.toArray();
                if (entries.length > 0) {
                    return entries[0];
                } else {
                    return undefined;
                }
            });
    }

    public setUserPermissions(userPermissions: UserPermissions): Promise<void> {
        return this.transaction('rw', this.userPermissionsTable, async () => {
            await this.userPermissionsTable.clear();
            await this.userPermissionsTable.put(userPermissions);
        });
    }

    public findAlbumsToSync(): Promise<AlbumData[]> {
        return this.transaction('r',
            this.albumDataTable,
            () => this.albumDataTable
                .filter(album => album.syncOffline > 0
                    && album.albumVersion === album.albumEntryVersion
                    && album.albumVersion !== album.offlineSyncedVersion)
                .toArray());
    }

    public setSync(albumId: string, minSize: number): Promise<void> {
        return this.transaction('rw', this.albumDataTable, async () => {
            const albumData = await this.albumDataTable.get(albumId);
            if (albumData !== undefined && albumData.syncOffline !== minSize) {
                albumData.syncOffline = minSize;
                albumData.offlineSyncedVersion = undefined;
                await this.albumDataTable.put(albumData);
            }
        });
    }

    public setOfflineSynced(id: string, albumEntryVersion: string): Promise<void> {
        return this.transaction('rw', this.albumDataTable, async () => {
            const alumData = await this.albumDataTable.get(id);
            if (alumData === undefined) {
                return;
            }
            alumData.offlineSyncedVersion = albumEntryVersion;
            await this.albumDataTable.put(alumData);
        });
    }
}

function isQuotaExceeded(ex: any) {
    return (ex.name === 'QuotaExceededError') ||
        (ex.inner && ex.inner.name === 'QuotaExceededError');

}
