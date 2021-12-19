import {Injectable} from '@angular/core';
import {HttpLink, HttpLinkHandler} from 'apollo-angular/http';
import {LoginService} from './login.service';
import {ApolloLink, InMemoryCache, split} from '@apollo/client/core';
import {setContext} from '@apollo/client/link/context';
import {Maybe} from '../generated/graphql';
import * as Apollo from 'apollo-angular';
import {ToastController} from '@ionic/angular';
import {Observable, of, throwError} from 'rxjs';
import {switchMap} from 'rxjs/operators';
import {getMainDefinition} from '@apollo/client/utilities';
import {MyWebSocketLink} from './web-socket-link';

@Injectable({
    providedIn: 'root'
})
export class ServerApiService {
    private readonly cache: InMemoryCache;
    private ready = false;

    constructor(
        private apollo: Apollo.Apollo,
        private httpLink: HttpLink,
        private login: LoginService,
        private toastController: ToastController
    ) {
        this.cache = new InMemoryCache({
            typePolicies: {
                Album: {
                    keyFields: ['id']
                },
                AlbumEntry: {
                    keyFields: ['id']
                }
            }
        });
        this.tryComeReady().then();
    }

    private async tryComeReady(): Promise<boolean> {
        if (this.ready) {
            return true;
        }
        if (await this.login.hasValidToken()) {
            try {
                const auth: ApolloLink = setContext((_, {headers}) => {
                    // Grab token if there is one in storage or hasn't expired

                    const token = this.login.currentValidToken();

                    // Return the headers as usual
                    return {
                        headers: {Token: token},
                    };
                });
                const httpLink: HttpLinkHandler = this.httpLink.create({uri: '/graphql'});

                const hostname = window.location.hostname;
                const port = window.location.port;
                const protocol = window.location.protocol;

                const wsProtocol = protocol.replace('http', 'ws');

                const wsUri = wsProtocol + '//' + hostname + ':' + port + '/graphqlws';


                const wsLink = new MyWebSocketLink({
                    url: wsUri,
                    connectionParams: () => {
                        const token = this.login.currentValidToken();
                        if (token) {
                            return {Token: token};
                        } else {
                            return {};
                        }
                    }
                });
                const ws = ApolloLink.from([auth, wsLink]);
                const splitLink = split(
                    ({query}) => {
                        const definition = getMainDefinition(query);
                        return (
                            definition.kind === 'OperationDefinition' &&
                            definition.operation === 'subscription'
                        );
                    },
                    ws,
                    httpLink,
                );

                this.apollo.create({
                    link: splitLink,
                    cache: this.cache,
                });
                this.ready = true;
            } catch (error) {
                console.error(error);
                this.ready = false;
            }
        } else {
            // console.warn('no valid token');
            this.ready = false;
        }
        return this.ready;
    }

    public async query<T, V>(query: Apollo.Query<T, V>, variables: V): Promise<Maybe<T>> {
        if (!await this.tryComeReady()) {
            console.error('not ready');
            // return Promise.resolve(null);
            return Promise.reject('Cannot init');
        }

        return new Promise<T>((resolve, reject) =>
            this.apollo.query({query: query.document, variables}).subscribe(result => {
                if (result.data) {
                    resolve(result.data as T);
                } else {
                    reject(result.errors);
                }
            }, error => {
                reject(error);
            })
        ).catch(error => {
            this.toastController.create({
                message: 'Error from server "' + error.message + '"',
                duration: 10000,
                color: 'danger'
            }).then(e => e.present());
            return null;
        });
    }

    public async update<T, V>(mutation: Apollo.Mutation<T, V>, variables: V): Promise<Maybe<T>> {
        if (!await this.tryComeReady()) {
            return Promise.reject('Cannot init');
        }
        return new Promise<T>((resolve, reject) => {
                return this.apollo
                    .mutate({mutation: mutation.document, variables})
                    .subscribe(result => {
                        if (result.data) {
                            resolve(result.data);
                        } else {
                            reject(result.errors);
                        }
                    }, error => {
                        reject(error);
                    });
            }
        ).catch(error => {
            this.toastController.create({
                message: 'Error from server "' + error.message + '"',
                duration: 10000,
                color: 'danger'
            }).then(e => e.present());
            return null;
        });

    }

    public subscribe<T, V>(subscription: Apollo.Subscription<T, V>, variables: V): Observable<T> {
        if (!this.tryComeReady()) {
            return throwError('Cannot init');
        }

        return this.apollo
            .subscribe({query: subscription.document, variables})
            .pipe(switchMap(result => {
                if (result.errors !== undefined) {
                    this.toastController.create({
                        message: 'Error from server "' + result.errors.join(', ') + '"',
                        duration: 10000,
                        color: 'danger'
                    }).then(e => e.present());
                    return throwError(result.errors);
                }
                return of(result.data);
            }));
    }


    clear(): Promise<void> {
        return this.cache.reset();
    }
}
