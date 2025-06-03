#include <mpi.h>
#include <omp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <openssl/aes.h>
#include <openssl/evp.h>

#define AES_BLOCK_SIZE 16

#define SAFE_FREE(p) do { if ((p)) { free(p); (p) = NULL; } } while(0)

// PKCS#7 unpadding
int pkcs7_unpad(const unsigned char* input, int input_len, unsigned char** output) {
    if (input_len == 0) return -1;
    int pad_len = input[input_len - 1];
    if (pad_len <= 0 || pad_len > AES_BLOCK_SIZE) return -1;

    for (int i = 0; i < pad_len; i++) {
        if (input[input_len - 1 - i] != pad_len) return -1;
    }

    int out_len = input_len - pad_len;
    *output = malloc(out_len);
    if (!*output) return -1;
    memcpy(*output, input, out_len);
    return out_len;
}

void get_decrypted_filename(const char* input_filename, char* output_filename) {
    size_t len = strlen(input_filename);
    const char* suffix = ".out";
    size_t suffix_len = strlen(suffix);
    if (len > suffix_len && strcmp(input_filename + len - suffix_len, suffix) == 0) {
        strncpy(output_filename, input_filename, len - suffix_len);
        output_filename[len - suffix_len] = '\0';
    } else {
        strcpy(output_filename, input_filename);
    }
}

int main(int argc, char** argv) {
    MPI_Init(&argc, &argv);
    int rank, size;
    MPI_Comm_rank(MPI_COMM_WORLD, &rank);
    MPI_Comm_size(MPI_COMM_WORLD, &size);

    char *filename = NULL, *operation = NULL, *mode = NULL;
    char *key_string = NULL, *iv_hex = NULL;
    int keylen_bits = 128;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--file") == 0) filename = argv[++i];
        else if (strcmp(argv[i], "--operation") == 0) operation = argv[++i];
        else if (strcmp(argv[i], "--mode") == 0) mode = argv[++i];
        else if (strcmp(argv[i], "--key") == 0) key_string = argv[++i];
        else if (strcmp(argv[i], "--iv") == 0) iv_hex = argv[++i];
        else if (strcmp(argv[i], "--keylen") == 0) keylen_bits = atoi(argv[++i]);
    }

    if (!filename || !operation || !mode || !key_string || (strcmp(mode, "cbc") == 0 && !iv_hex)) {
        if (rank == 0) fprintf(stderr, "Missing required arguments\n");
        MPI_Finalize();
        return 1;
    }

    int key_len_bytes = keylen_bits / 8;
    unsigned char key[32] = {0};
    unsigned char iv[16] = {0};
    key_string[strcspn(key_string, "\r\n")] = '\0';

    if ((int)strlen(key_string) != key_len_bytes) {
        if (rank == 0) fprintf(stderr, "Invalid key length\n");
        MPI_Finalize();
        return 1;
    }
    memcpy(key, key_string, key_len_bytes);

    if (strcmp(mode, "cbc") == 0) {
        if (strlen(iv_hex) != 32) {
            if (rank == 0) fprintf(stderr, "IV must be 32 hex characters (16 bytes) in CBC mode\n");
            MPI_Finalize();
            return 1;
        }

        #pragma omp parallel for
        for (int i = 0; i < AES_BLOCK_SIZE; i++) {
            sscanf(iv_hex + 2 * i, "%2hhx", &iv[i]);
        }
    }

    unsigned char *file_buffer = NULL;
    int file_size = 0;
    if (rank == 0) {
        FILE* f = fopen(filename, "rb");
        if (!f) {
            fprintf(stderr, "Cannot open file %s\n", filename);
            MPI_Abort(MPI_COMM_WORLD, 1);
        }
        fseek(f, 0, SEEK_END);
        file_size = ftell(f);
        fseek(f, 0, SEEK_SET);
        file_buffer = malloc(file_size);
        fread(file_buffer, 1, file_size, f);
        fclose(f);

        if (strcmp(operation, "encrypt") == 0) {
            int pad_len = AES_BLOCK_SIZE - (file_size % AES_BLOCK_SIZE);
            if (pad_len == 0) pad_len = AES_BLOCK_SIZE;
            int padded_size = file_size + pad_len;
            unsigned char *new_buffer = realloc(file_buffer, padded_size);
            if (!new_buffer) {
                fprintf(stderr, "Memory allocation failed for padding\n");
                free(file_buffer);
                MPI_Abort(MPI_COMM_WORLD, 1);
            }
            file_buffer = new_buffer;

            #pragma omp parallel for
            for (int i = 0; i < pad_len; i++) {
                file_buffer[file_size + i] = pad_len;
            }
            file_size = padded_size;
        } else {
            if (file_size % AES_BLOCK_SIZE != 0) {
                fprintf(stderr, "Data size must be multiple of AES block size for decryption\n");
                free(file_buffer);
                MPI_Abort(MPI_COMM_WORLD, 1);
            }
        }
    }

    MPI_Bcast(&file_size, 1, MPI_INT, 0, MPI_COMM_WORLD);

    if (file_size % AES_BLOCK_SIZE != 0) {
        if (rank == 0) fprintf(stderr, "Data size must be multiple of AES block size\n");
        MPI_Abort(MPI_COMM_WORLD, 1);
    }

    int total_blocks = file_size / AES_BLOCK_SIZE;
    int blocks_per_proc = total_blocks / size;
    int blocks_remainder = total_blocks % size;

    int local_blocks = blocks_per_proc + (rank < blocks_remainder ? 1 : 0);
    int local_size = local_blocks * AES_BLOCK_SIZE;

    int *counts = malloc(size * sizeof(int));
    int *displs = malloc(size * sizeof(int));
    int offset_blocks = 0;

    for (int i = 0; i < size; i++) {
        int blocks = blocks_per_proc + (i < blocks_remainder ? 1 : 0);
        counts[i] = blocks * AES_BLOCK_SIZE;
        displs[i] = offset_blocks * AES_BLOCK_SIZE;
        offset_blocks += blocks;
    }

    unsigned char *local_data_in = malloc(local_size);
    MPI_Scatterv(file_buffer, counts, displs, MPI_UNSIGNED_CHAR,
                 local_data_in, local_size, MPI_UNSIGNED_CHAR,
                 0, MPI_COMM_WORLD);

    if (rank == 0) SAFE_FREE(file_buffer);

    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    const EVP_CIPHER* cipher = NULL;
    if (strcmp(mode, "cbc") == 0) {
        if (key_len_bytes == 16) cipher = EVP_aes_128_cbc();
        else if (key_len_bytes == 24) cipher = EVP_aes_192_cbc();
        else if (key_len_bytes == 32) cipher = EVP_aes_256_cbc();
    } else if (strcmp(mode, "ecb") == 0) {
        if (key_len_bytes == 16) cipher = EVP_aes_128_ecb();
        else if (key_len_bytes == 24) cipher = EVP_aes_192_ecb();
        else if (key_len_bytes == 32) cipher = EVP_aes_256_ecb();
    }

    if (!cipher) {
        if (rank == 0) fprintf(stderr, "Unsupported AES mode or key length\n");
        SAFE_FREE(local_data_in);
        free(counts);
        free(displs);
        EVP_CIPHER_CTX_free(ctx);
        MPI_Finalize();
        return 1;
    }

    int init_result = (strcmp(operation, "encrypt") == 0) ?
        EVP_EncryptInit_ex(ctx, cipher, NULL, key, strcmp(mode, "ecb") == 0 ? NULL : iv) :
        EVP_DecryptInit_ex(ctx, cipher, NULL, key, strcmp(mode, "ecb") == 0 ? NULL : iv);

    if (!init_result) {
        fprintf(stderr, "EVP init failed\n");
        SAFE_FREE(local_data_in);
        free(counts);
        free(displs);
        EVP_CIPHER_CTX_free(ctx);
        MPI_Abort(MPI_COMM_WORLD, 1);
    }

    EVP_CIPHER_CTX_set_padding(ctx, 0);

    unsigned char *local_out = malloc(local_size);
    int block_count = local_size / AES_BLOCK_SIZE;
    int thread_error = 0;

    #pragma omp parallel
    {
        EVP_CIPHER_CTX *tctx = EVP_CIPHER_CTX_new();
        EVP_CIPHER_CTX_copy(tctx, ctx);
        EVP_CIPHER_CTX_set_padding(tctx, 0);

        #pragma omp for
        for (int b = 0; b < block_count; b++) {
            int tlen = 0;
            unsigned char *in_ptr = local_data_in + b * AES_BLOCK_SIZE;
            unsigned char *out_ptr = local_out + b * AES_BLOCK_SIZE;

            int res = (strcmp(operation, "encrypt") == 0) ?
                EVP_EncryptUpdate(tctx, out_ptr, &tlen, in_ptr, AES_BLOCK_SIZE) :
                EVP_DecryptUpdate(tctx, out_ptr, &tlen, in_ptr, AES_BLOCK_SIZE);

            if (!res) {
                #pragma omp critical
                thread_error = 1;
            }
        }

        EVP_CIPHER_CTX_free(tctx);
    }

    if (thread_error) {
        fprintf(stderr, "%s failed on rank %d\n", operation, rank);
        SAFE_FREE(local_data_in);
        SAFE_FREE(local_out);
        free(counts);
        free(displs);
        EVP_CIPHER_CTX_free(ctx);
        MPI_Abort(MPI_COMM_WORLD, 1);
    }

    SAFE_FREE(local_data_in);

    if (rank == 0) {
        int final_len = 0;
        if (strcmp(operation, "encrypt") == 0)
            EVP_EncryptFinal_ex(ctx, local_out + local_size, &final_len);
        else
            EVP_DecryptFinal_ex(ctx, local_out + local_size, &final_len);
    }

    EVP_CIPHER_CTX_free(ctx);

    unsigned char *output_buffer = NULL;
    if (rank == 0) {
        output_buffer = malloc(file_size);
    }

    MPI_Gatherv(local_out, local_size, MPI_UNSIGNED_CHAR,
                output_buffer, counts, displs, MPI_UNSIGNED_CHAR,
                0, MPI_COMM_WORLD);

    SAFE_FREE(local_out);
    free(counts);
    free(displs);

    if (rank == 0) {
        unsigned char* final_data = NULL;
        int final_size = 0;
        int need_to_free_final_data = 0;

        if (strcmp(operation, "decrypt") == 0) {
            final_size = pkcs7_unpad(output_buffer, file_size, &final_data);
            if (final_size < 0) {
                fprintf(stderr, "Invalid PKCS#7 padding\n");
                SAFE_FREE(output_buffer);
                MPI_Finalize();
                return 1;
            }
            need_to_free_final_data = 1;
        } else {
            final_data = output_buffer;
            final_size = file_size;
            need_to_free_final_data = 0;
        }

        char output_filename[1024];
        if (strcmp(operation, "decrypt") == 0)
            get_decrypted_filename(filename, output_filename);
        else
            snprintf(output_filename, sizeof(output_filename), "%s.out", filename);

        FILE *fout = fopen(output_filename, "wb");
        if (!fout) {
            fprintf(stderr, "Cannot open output file %s\n", output_filename);
            if (need_to_free_final_data) SAFE_FREE(final_data);
            else SAFE_FREE(output_buffer);
            MPI_Finalize();
            return 1;
        }
        fwrite(final_data, 1, final_size, fout);
        fclose(fout);

        if (need_to_free_final_data)
            SAFE_FREE(final_data);
        else
            SAFE_FREE(output_buffer);

        printf("Operation '%s' completed, output file: %s\n", operation, output_filename);
    }

    MPI_Finalize();
    return 0;
}
