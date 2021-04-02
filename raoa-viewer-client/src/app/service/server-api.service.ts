import {Injectable} from '@angular/core';
import {HttpLink} from 'apollo-angular/http';
import {LoginService} from './login.service';
import {ApolloLink, InMemoryCache} from '@apollo/client/core';
import {setContext} from '@apollo/client/link/context';
import {Maybe} from '../generated/graphql';
import * as Apollo from 'apollo-angular';
import {ToastController} from '@ionic/angular';


@Injectable({
    providedIn: 'root'
})
export class ServerApiService {
    private readyPromise: Promise<boolean>;
    private cache: InMemoryCache;

    constructor(
        private apollo: Apollo.Apollo,
        httpLink: HttpLink,
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

        this.readyPromise =
            login.signedInUser().then(user => {
                try {
                    const auth: ApolloLink = setContext(async (_, {headers}) => {
                        // Grab token if there is one in storage or hasn't expired
                        // const token = await this.login.idToken();
                        console.log(headers);

                        // Return the headers as usual
                        return {
                            headers: {
                                // Authorization: `Bearer ${token}`,
                            },
                        };
                    });
                    const http = ApolloLink.from([auth, httpLink.create({uri: '/graphql'})]);
                    // const http = httpLink.create({uri: '/graphql'});
                    apollo.create({
                        link: http,
                        cache: this.cache,
                    });
                    this.readyPromise = Promise.resolve(true);
                    return true;
                } catch (error) {
                    console.log(error);
                    return false;
                }
            }).catch(error => {
                console.log('error');
                console.log(error);
                return false;
            });
    }

    public async query<T, V>(query: Apollo.Query<T, V>, variables: V): Promise<Maybe<T>> {
        if (!(await this.readyPromise)) {
            console.log('not ready');
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
        if (!(await this.readyPromise)) {
            return Promise.reject('Cannot init');
        }
        return new Promise<T>((resolve, reject) => {
                return mutation.mutate(variables).subscribe(result => {
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

    clear(): Promise<void> {
        return this.cache.reset();
    }
}
